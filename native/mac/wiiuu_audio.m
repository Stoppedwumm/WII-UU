// WII-UU sound capture for macOS: a JNI library in Objective-C (wiiuu.screen.MacAudio).
//
// It records everything the Mac plays and hands it to Java as 16-bit little-endian stereo PCM at
// the rate asked for. Two ways, tried in turn:
//   1. ScreenCaptureKit (macOS 13+), with the Screen Recording permission WII-UU already has for
//      the picture;
//   2. a Core Audio tap on the system output (macOS 14.2+), if ScreenCaptureKit refuses or doesn't
//      answer within a few seconds (it can wait on a permission dialog, or stall).
// Built once by GitHub Actions on a Mac (native/mac/build.sh, .github/workflows/mac-native.yml) for
// Apple Silicon and Intel, and shipped in wiiuu.jar, so the Mac needs no Xcode Command Line Tools.
// Progress goes to stderr, which WII-UU keeps in ~/.wiiuu/logs/audio.log.
// WIIUU_MAC_AUDIO=sck or =tap picks one way only (for testing).
#import <Foundation/Foundation.h>
#import <CoreAudio/CoreAudio.h>
#import <CoreAudio/AudioHardwareTapping.h>
#import <CoreAudio/CATapDescription.h>
#import <CoreMedia/CoreMedia.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>

static void note(NSString *message) {
    fprintf(stderr, "[mac audio] %s\n", message.UTF8String);
    fflush(stderr);
}

// ---- a ring buffer between the capture's queue and Java's read() ------------------------------

#define RING_BYTES (48000 * 4 * 2)        // two seconds of 48 kHz stereo 16-bit

static uint8_t ring[RING_BYTES];
static size_t ringStart, ringCount;        // guarded by lock
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t ready = PTHREAD_COND_INITIALIZER;
static char problem[1024];                 // why capture stopped, once it has
static volatile int stopped;

static void fail(NSString *message) {
    note(message);
    pthread_mutex_lock(&lock);
    if (!problem[0]) strlcpy(problem, message.UTF8String ?: "sound capture failed", sizeof problem);
    stopped = 1;
    pthread_cond_broadcast(&ready);
    pthread_mutex_unlock(&lock);
}

static void push(const int16_t *samples, size_t frames) {
    const uint8_t *bytes = (const uint8_t *) samples;
    size_t n = frames * 4;
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

// ---- turning what macOS delivers into 16-bit stereo at the asked rate --------------------------

static int targetRate = 48000;
static double resamplePos;                 // position between the previous and next source frame
static float lastL, lastR;                 // the previous source frame

/** One source frame (left, right) from an AudioBufferList of any common layout. */
static void frameAt(const AudioBufferList *list, const AudioStreamBasicDescription *asbd, size_t f, float *l, float *r) {
    UInt32 channels = MAX(1, asbd->mChannelsPerFrame);
    BOOL isFloat = (asbd->mFormatFlags & kAudioFormatFlagIsFloat) != 0;
    BOOL planar = (asbd->mFormatFlags & kAudioFormatFlagIsNonInterleaved) != 0;
    float out[2] = {0, 0};
    for (UInt32 c = 0; c < 2; c++) {
        UInt32 ch = MIN(c, channels - 1);
        const AudioBuffer *buffer = &list->mBuffers[planar ? MIN(ch, list->mNumberBuffers - 1) : 0];
        if (!buffer->mData) continue;
        size_t index = planar ? f : f * channels + ch;
        out[c] = isFloat ? ((const float *) buffer->mData)[index] : ((const int16_t *) buffer->mData)[index] / 32768.0f;
    }
    *l = out[0];
    *r = out[1];
}

static int16_t pcm(float s) {
    s = s > 1 ? 1 : s < -1 ? -1 : s;
    return (int16_t) (s * 32767);
}

/** Converts {@code frames} source frames at {@code sourceRate} (linear resampling) and queues them. */
static void deliver(const AudioBufferList *list, const AudioStreamBasicDescription *asbd, size_t frames, double sourceRate) {
    if (frames == 0) return;
    double step = sourceRate / targetRate;
    size_t capacity = (size_t) (frames / step) + 4;
    int16_t *out = malloc(capacity * 4);
    size_t n = 0;
    for (size_t f = 0; f < frames; f++) {
        float l, r;
        frameAt(list, asbd, f, &l, &r);
        // emit every output frame that falls between the previous source frame and this one
        while (resamplePos <= 1.0 && n < capacity) {
            out[n * 2] = pcm(lastL + (l - lastL) * (float) resamplePos);
            out[n * 2 + 1] = pcm(lastR + (r - lastR) * (float) resamplePos);
            n++;
            resamplePos += step;
        }
        resamplePos -= 1.0;
        lastL = l;
        lastR = r;
    }
    push(out, n);
    free(out);
}

// ---- which way is running ----------------------------------------------------------------------

static pthread_mutex_t startLock = PTHREAD_MUTEX_INITIALIZER;
static int running;                        // 0 none yet, 1 ScreenCaptureKit, 2 Core Audio tap
static int sckGaveUp, tapTried;            // guarded by startLock
static id sckStream;                       // kept alive while capturing

static void startTap(void);

// ---- 1. ScreenCaptureKit ------------------------------------------------------------------------

API_AVAILABLE(macos(13.0))
@interface WiiuuAudio : NSObject <SCStreamOutput, SCStreamDelegate>
@end

@implementation WiiuuAudio

- (void)stream:(SCStream *)stream didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer ofType:(SCStreamOutputType)type {
    if (type != SCStreamOutputTypeAudio || running != 1 || !CMSampleBufferIsValid(sampleBuffer)) return;
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
            kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, &block) == noErr) {
        deliver(list, asbd, (size_t) frames, asbd->mSampleRate > 0 ? asbd->mSampleRate : targetRate);
    }
    if (block) CFRelease(block);
    free(list);
}

- (void)stream:(SCStream *)stream didStopWithError:(NSError *)error {
    if (running == 1) fail([@"ScreenCaptureKit stopped: " stringByAppendingString:error.localizedDescription ?: @"unknown error"]);
}

@end

/** ScreenCaptureKit failed or is too slow: the tap instead (once), or give up if there is none. */
static void sckDidNotStart(NSString *why) {
    pthread_mutex_lock(&startLock);
    BOOL first = !sckGaveUp && running == 0;
    sckGaveUp = 1;
    pthread_mutex_unlock(&startLock);
    if (!first) return;
    note(why);
    startTap();
}

static void startSck(void) API_AVAILABLE(macos(13.0)) {
    note(@"asking ScreenCaptureKit for the sound");
    [SCShareableContent getShareableContentExcludingDesktopWindows:NO onScreenWindowsOnly:YES
            completionHandler:^(SCShareableContent *content, NSError *contentError) {
        if (contentError || content.displays.count == 0) {
            sckDidNotStart(contentError
                    ? [NSString stringWithFormat:@"ScreenCaptureKit refused: %@ (Screen Recording permission?)", contentError.localizedDescription]
                    : @"ScreenCaptureKit found no display");
            return;
        }
        SCStreamConfiguration *config = [SCStreamConfiguration new];
        config.capturesAudio = YES;
        config.excludesCurrentProcessAudio = YES;
        config.sampleRate = targetRate;
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
                  sampleHandlerQueue:dispatch_queue_create("wiiuu.audio.sck", DISPATCH_QUEUE_SERIAL) error:&addError]) {
            sckDidNotStart([NSString stringWithFormat:@"ScreenCaptureKit: %@", addError.localizedDescription]);
            return;
        }
        [stream startCaptureWithCompletionHandler:^(NSError *startError) {
            if (startError) {
                sckDidNotStart([NSString stringWithFormat:@"ScreenCaptureKit could not start: %@ (Screen Recording permission?)",
                                                          startError.localizedDescription]);
                return;
            }
            pthread_mutex_lock(&startLock);
            BOOL use = running == 0;
            if (use) running = 1;
            pthread_mutex_unlock(&startLock);
            if (use) {
                sckStream = @[stream, output];
                note(@"capturing with ScreenCaptureKit");
            } else {
                [stream stopCaptureWithCompletionHandler:^(NSError *e) {}];      // the tap won the race
            }
        }];
    }];
}

// ---- 2. a Core Audio tap on the system output (macOS 14.2+) -------------------------------------

static AudioObjectID tapId = kAudioObjectUnknown, aggregateId = kAudioObjectUnknown;
static AudioDeviceIOProcID ioProc;
static AudioStreamBasicDescription tapFormat;

static NSString *outputDeviceUid(void) {
    AudioObjectPropertyAddress a = {kAudioHardwarePropertyDefaultSystemOutputDevice, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
    AudioObjectID device = kAudioObjectUnknown;
    UInt32 size = sizeof device;
    if (AudioObjectGetPropertyData(kAudioObjectSystemObject, &a, 0, NULL, &size, &device) != noErr) return nil;
    a.mSelector = kAudioDevicePropertyDeviceUID;
    CFStringRef uid = NULL;
    size = sizeof uid;
    if (AudioObjectGetPropertyData(device, &a, 0, NULL, &size, &uid) != noErr || !uid) return nil;
    return (__bridge_transfer NSString *) uid;
}

static void startTap(void) {
    pthread_mutex_lock(&startLock);
    BOOL first = !tapTried;
    tapTried = 1;
    pthread_mutex_unlock(&startLock);
    if (!first) return;
    if (@available(macOS 14.2, *)) {
        note(@"trying a Core Audio tap on the system output (macOS may ask to allow \"System Audio Recording\")");
        CATapDescription *tap = [[CATapDescription alloc] initStereoGlobalTapButExcludeProcesses:@[]];
        tap.name = @"WII-UU sound";
        tap.privateTap = YES;
        tap.muteBehavior = CATapUnmuted;
        OSStatus st = AudioHardwareCreateProcessTap(tap, &tapId);
        note([NSString stringWithFormat:@"tap created (%d)", (int) st]);
        if (st != noErr) {
            fail([NSString stringWithFormat:@"no sound: ScreenCaptureKit didn't start, and the Core Audio tap failed (%d)", (int) st]);
            return;
        }
        AudioObjectPropertyAddress fa = {kAudioTapPropertyFormat, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
        UInt32 size = sizeof tapFormat;
        AudioObjectGetPropertyData(tapId, &fa, 0, NULL, &size, &tapFormat);
        NSString *output = outputDeviceUid();
        if (!output) {
            fail(@"no sound: ScreenCaptureKit didn't start, and the default output device was not found");
            return;
        }
        NSDictionary *description = @{
            @kAudioAggregateDeviceNameKey: @"WII-UU sound",
            @kAudioAggregateDeviceUIDKey: [NSUUID UUID].UUIDString,
            @kAudioAggregateDeviceMainSubDeviceKey: output,
            @kAudioAggregateDeviceIsPrivateKey: @YES,
            @kAudioAggregateDeviceIsStackedKey: @NO,
            @kAudioAggregateDeviceTapAutoStartKey: @YES,
            @kAudioAggregateDeviceSubDeviceListKey: @[@{@kAudioSubDeviceUIDKey: output}],
            @kAudioAggregateDeviceTapListKey: @[@{@kAudioSubTapDriftCompensationKey: @YES, @kAudioSubTapUIDKey: tap.UUID.UUIDString}],
        };
        st = AudioHardwareCreateAggregateDevice((__bridge CFDictionaryRef) description, &aggregateId);
        note([NSString stringWithFormat:@"tap device created (%d)", (int) st]);
        if (st != noErr) {
            fail([NSString stringWithFormat:@"no sound: ScreenCaptureKit didn't start, and the tap's device failed (%d)", (int) st]);
            return;
        }
        st = AudioDeviceCreateIOProcIDWithBlock(&ioProc, aggregateId, dispatch_queue_create("wiiuu.audio.tap", DISPATCH_QUEUE_SERIAL),
                ^(const AudioTimeStamp *now, const AudioBufferList *input, const AudioTimeStamp *inputTime,
                  AudioBufferList *output, const AudioTimeStamp *outputTime) {
            if (running != 2 || !input || input->mNumberBuffers == 0) return;
            BOOL planar = (tapFormat.mFormatFlags & kAudioFormatFlagIsNonInterleaved) != 0;
            UInt32 bytesPerSample = MAX(1, tapFormat.mBitsPerChannel / 8);
            UInt32 perFrame = planar ? bytesPerSample : MAX(1, tapFormat.mBytesPerFrame);
            deliver(input, &tapFormat, input->mBuffers[0].mDataByteSize / perFrame,
                    tapFormat.mSampleRate > 0 ? tapFormat.mSampleRate : 48000);
        });
        if (st == noErr) {
            pthread_mutex_lock(&startLock);
            BOOL use = running == 0;
            if (use) running = 2;
            pthread_mutex_unlock(&startLock);
            if (!use) return;                                      // ScreenCaptureKit came through after all
            st = AudioDeviceStart(aggregateId, ioProc);
            note([NSString stringWithFormat:@"tap device started (%d)", (int) st]);
        }
        if (st != noErr) {
            fail([NSString stringWithFormat:@"no sound: ScreenCaptureKit didn't start, and the tap could not start (%d)", (int) st]);
            return;
        }
        note([NSString stringWithFormat:@"capturing with a Core Audio tap (%.0f Hz, %u channels)", tapFormat.mSampleRate,
                                        (unsigned) tapFormat.mChannelsPerFrame]);
    } else {
        fail(@"no sound: ScreenCaptureKit didn't start (allow WII-UU, or Java / Terminal, under Screen Recording); "
              "macOS 14.2 or newer could also use a Core Audio tap");
    }
}

// ---- JNI ----------------------------------------------------------------------------------------

/**
 * Starts capturing at {@code rate} Hz without waiting for macOS: null, or why it can't even try.
 * Sound arrives through read() once one of the ways is running.
 */
JNIEXPORT jstring JNICALL Java_wiiuu_screen_MacAudio_start(JNIEnv *env, jclass cls, jint rate) {
    targetRate = rate > 0 ? rate : 48000;
    const char *only = getenv("WIIUU_MAC_AUDIO");
    if (only && strcmp(only, "tap") == 0) {
        sckGaveUp = 1;
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{ startTap(); });
        return NULL;
    }
    if (@available(macOS 13.0, *)) {
        startSck();
        if (!(only && strcmp(only, "sck") == 0)) {
            // ScreenCaptureKit can wait on a permission dialog, or stall: don't wait for it for long
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC), dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
                if (running == 0) sckDidNotStart(@"ScreenCaptureKit hasn't answered in 5 s (a permission dialog may be waiting)");
            });
        }
        return NULL;
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
    int was = running;
    running = 0;
    if (was == 1) {
        if (@available(macOS 13.0, *)) {
            NSArray *s = sckStream;
            if (s) [(SCStream *) s[0] stopCaptureWithCompletionHandler:^(NSError *e) {}];
        }
    } else if (was == 2) {
        AudioDeviceStop(aggregateId, ioProc);
        AudioDeviceDestroyIOProcID(aggregateId, ioProc);
        AudioHardwareDestroyAggregateDevice(aggregateId);
        if (@available(macOS 14.2, *)) AudioHardwareDestroyProcessTap(tapId);
    }
    fail(@"stopped");
}

/** The library's version, to check that it loads. */
JNIEXPORT jint JNICALL Java_wiiuu_screen_MacAudio_version(JNIEnv *env, jclass cls) {
    return 3;
}
