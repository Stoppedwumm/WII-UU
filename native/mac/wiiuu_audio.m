// WII-UU sound capture for macOS: a JNI library in Objective-C on ScreenCaptureKit (macOS 13+).
//
// It records everything the Mac plays and hands it to Java (wiiuu.screen.MacAudio) as 16-bit
// little-endian stereo PCM. Unlike the Swift helper it replaces, it is built once, by GitHub
// Actions on a Mac (native/mac/build.sh, .github/workflows/mac-native.yml), for Apple Silicon and
// Intel, and ships inside wiiuu.jar, so the Mac needs no Xcode Command Line Tools.
//
// Java side: MacAudio runs in its own small Java process, so a problem here never takes WII-UU
// down; it calls start(rate) once, then read() in a loop, and writes what it gets to stdout.
#import <Foundation/Foundation.h>
#import <CoreAudio/CoreAudio.h>
#import <CoreMedia/CoreMedia.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#include <jni.h>
#include <pthread.h>
#include <string.h>

// ---- a ring buffer between ScreenCaptureKit's queue and Java's read() ---------------------------

#define RING_BYTES (48000 * 4 * 2)        // two seconds of 48 kHz stereo 16-bit

static uint8_t ring[RING_BYTES];
static size_t ringStart, ringCount;        // guarded by lock
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t ready = PTHREAD_COND_INITIALIZER;
static char problem[512];                  // why capture stopped, once it has
static volatile int stopped;

static void fail(NSString *message) {
    pthread_mutex_lock(&lock);
    if (!problem[0]) strlcpy(problem, message.UTF8String ?: "sound capture failed", sizeof problem);
    stopped = 1;
    pthread_cond_broadcast(&ready);
    pthread_mutex_unlock(&lock);
}

static void push(const uint8_t *bytes, size_t n) {
    pthread_mutex_lock(&lock);
    for (size_t i = 0; i < n; i++) {
        if (ringCount == RING_BYTES) {                     // Java fell behind: drop the oldest
            ringStart = (ringStart + 1) % RING_BYTES;
            ringCount--;
        }
        ring[(ringStart + ringCount) % RING_BYTES] = bytes[i];
        ringCount++;
    }
    pthread_cond_broadcast(&ready);
    pthread_mutex_unlock(&lock);
}

// ---- ScreenCaptureKit -------------------------------------------------------------------------

API_AVAILABLE(macos(13.0))
@interface WiiuuAudio : NSObject <SCStreamOutput, SCStreamDelegate>
@end

@implementation WiiuuAudio

- (void)stream:(SCStream *)stream didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer ofType:(SCStreamOutputType)type {
    if (type != SCStreamOutputTypeAudio || !CMSampleBufferIsValid(sampleBuffer)) return;
    CMFormatDescriptionRef format = CMSampleBufferGetFormatDescription(sampleBuffer);
    const AudioStreamBasicDescription *asbd = format ? CMAudioFormatDescriptionGetStreamBasicDescription(format) : NULL;
    if (!asbd) return;
    CMItemCount frames = CMSampleBufferGetNumSamples(sampleBuffer);
    size_t needed = 0;
    CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sampleBuffer, &needed, NULL, 0, NULL, NULL, 0, NULL);
    if (frames <= 0 || needed == 0) return;
    AudioBufferList *list = malloc(needed);
    CMBlockBufferRef block = NULL;
    if (CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sampleBuffer, NULL, list, needed, NULL, NULL,
            kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, &block) != noErr) {
        free(list);
        return;
    }
    // interleave to stereo 16-bit, whatever layout macOS delivers (usually planar float)
    UInt32 channels = MAX(1, asbd->mChannelsPerFrame);
    BOOL isFloat = (asbd->mFormatFlags & kAudioFormatFlagIsFloat) != 0;
    BOOL planar = (asbd->mFormatFlags & kAudioFormatFlagIsNonInterleaved) != 0;
    int16_t *out = malloc((size_t) frames * 4);
    for (CMItemCount f = 0; f < frames; f++) {
        for (UInt32 c = 0; c < 2; c++) {
            UInt32 ch = MIN(c, channels - 1);
            AudioBuffer buffer = list->mBuffers[planar ? MIN(ch, list->mNumberBuffers - 1) : 0];
            float s = 0;
            if (buffer.mData) {
                size_t index = planar ? (size_t) f : (size_t) f * channels + ch;
                s = isFloat ? ((const float *) buffer.mData)[index] : ((const int16_t *) buffer.mData)[index] / 32768.0f;
            }
            s = s > 1 ? 1 : s < -1 ? -1 : s;
            out[f * 2 + c] = (int16_t) (s * 32767);      // the Mac is little-endian, like the PCM WII-UU sends
        }
    }
    push((const uint8_t *) out, (size_t) frames * 4);
    free(out);
    if (block) CFRelease(block);
    free(list);
}

- (void)stream:(SCStream *)stream didStopWithError:(NSError *)error {
    fail([@"sound capture stopped: " stringByAppendingString:error.localizedDescription ?: @"unknown error"]);
}

@end

static id running;                         // the stream and its output, kept alive while capturing

// ---- JNI --------------------------------------------------------------------------------------

/** Starts capturing at {@code rate} Hz; null when it runs, else why not. */
JNIEXPORT jstring JNICALL Java_wiiuu_screen_MacAudio_start(JNIEnv *env, jclass cls, jint rate) {
    if (@available(macOS 13.0, *)) {
        __block NSString *error = nil;
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        [SCShareableContent getShareableContentExcludingDesktopWindows:NO onScreenWindowsOnly:YES
                completionHandler:^(SCShareableContent *content, NSError *contentError) {
            if (contentError || content.displays.count == 0) {
                error = contentError
                        ? [NSString stringWithFormat:@"sound capture failed: %@ - is Screen Recording allowed for WII-UU?", contentError.localizedDescription]
                        : @"no display found";
                dispatch_semaphore_signal(done);
                return;
            }
            SCStreamConfiguration *config = [SCStreamConfiguration new];
            config.capturesAudio = YES;
            config.excludesCurrentProcessAudio = YES;
            config.sampleRate = rate;
            config.channelCount = 2;
            // a picture is required, so ask for a tiny, rare one and ignore it
            config.width = 2;
            config.height = 2;
            config.minimumFrameInterval = CMTimeMake(1, 1);
            config.showsCursor = NO;
            SCContentFilter *filter = [[SCContentFilter alloc] initWithDisplay:content.displays.firstObject excludingWindows:@[]];
            WiiuuAudio *output = [WiiuuAudio new];
            SCStream *stream = [[SCStream alloc] initWithFilter:filter configuration:config delegate:output];
            NSError *addError = nil;
            if (![stream addStreamOutput:output type:SCStreamOutputTypeAudio
                      sampleHandlerQueue:dispatch_queue_create("wiiuu.audio", DISPATCH_QUEUE_SERIAL) error:&addError]) {
                error = [NSString stringWithFormat:@"sound capture failed: %@", addError.localizedDescription];
                dispatch_semaphore_signal(done);
                return;
            }
            [stream startCaptureWithCompletionHandler:^(NSError *startError) {
                if (startError) {
                    error = [NSString stringWithFormat:@"sound capture failed: %@ - is Screen Recording allowed for WII-UU?",
                                                       startError.localizedDescription];
                } else {
                    running = @[stream, output];
                }
                dispatch_semaphore_signal(done);
            }];
        }];
        if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 20 * NSEC_PER_SEC)) != 0) {
            error = @"sound capture did not start within 20 s";
        }
        return error ? (*env)->NewStringUTF(env, error.UTF8String) : NULL;
    }
    return (*env)->NewStringUTF(env, "sound needs macOS 13 (Ventura) or newer");
}

/**
 * Copies captured sound into {@code buffer}, waiting up to {@code timeoutMs} for some: the number
 * of bytes (a whole number of frames), 0 if none came, -1 once capture has stopped (see error()).
 */
JNIEXPORT jint JNICALL Java_wiiuu_screen_MacAudio_read(JNIEnv *env, jclass cls, jbyteArray buffer, jint timeoutMs) {
    jsize capacity = (*env)->GetArrayLength(env, buffer) & ~3;
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += timeoutMs / 1000;
    deadline.tv_nsec += (long) (timeoutMs % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec++;
        deadline.tv_nsec -= 1000000000L;
    }
    pthread_mutex_lock(&lock);
    while (ringCount < 4 && !stopped) {
        if (pthread_cond_timedwait(&ready, &lock, &deadline) != 0) break;
    }
    if (ringCount < 4) {
        int result = stopped ? -1 : 0;
        pthread_mutex_unlock(&lock);
        return result;
    }
    size_t n = MIN((size_t) capacity, ringCount) & ~(size_t) 3;
    jbyte *dst = (*env)->GetPrimitiveArrayCritical(env, buffer, NULL);
    for (size_t i = 0; i < n; i++) dst[i] = (jbyte) ring[(ringStart + i) % RING_BYTES];
    (*env)->ReleasePrimitiveArrayCritical(env, buffer, dst, 0);
    ringStart = (ringStart + n) % RING_BYTES;
    ringCount -= n;
    pthread_mutex_unlock(&lock);
    return (jint) n;
}

/** Why capture stopped, or null. */
JNIEXPORT jstring JNICALL Java_wiiuu_screen_MacAudio_error(JNIEnv *env, jclass cls) {
    pthread_mutex_lock(&lock);
    jstring s = problem[0] ? (*env)->NewStringUTF(env, problem) : NULL;
    pthread_mutex_unlock(&lock);
    return s;
}

/** Stops capturing. */
JNIEXPORT void JNICALL Java_wiiuu_screen_MacAudio_stop(JNIEnv *env, jclass cls) {
    if (@available(macOS 13.0, *)) {
        NSArray *r = running;
        running = nil;
        if (r) [(SCStream *) r[0] stopCaptureWithCompletionHandler:^(NSError *e) {}];
    }
    fail(@"stopped");
}

/** The library's version, to check that it loads. */
JNIEXPORT jint JNICALL Java_wiiuu_screen_MacAudio_version(JNIEnv *env, jclass cls) {
    return 1;
}
