// WII-UU sound capture helper for macOS, built on ScreenCaptureKit (macOS 13+).
//
// usage:  wiiuu-audio <sampleRate>
// output: everything the Mac plays, on stdout as raw 16-bit little-endian stereo PCM.
//
// WII-UU compiles this itself with the Xcode Command Line Tools (xcrun swiftc) the first time.
// It is separate from the picture helper (capture.swift), so a problem here never costs the picture.
import CoreAudio
import CoreMedia
import Foundation
import ScreenCaptureKit

signal(SIGPIPE, SIG_DFL)          // WII-UU stops reading -> exit

var keepAlive: AnyObject?         // the running capture stream

func fail(_ message: String) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(1)
}

let args = CommandLine.arguments

// Sound: ScreenCaptureKit can record everything the Mac plays (macOS 13+).
@available(macOS 13.0, *)
final class AudioWriter: NSObject, SCStreamOutput, SCStreamDelegate {
    private let stdout = FileHandle.standardOutput

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio, sampleBuffer.isValid,
              let format = sampleBuffer.formatDescription,
              let asbdPointer = CMAudioFormatDescriptionGetStreamBasicDescription(format) else { return }
        let asbd = asbdPointer.pointee
        let frames = CMSampleBufferGetNumSamples(sampleBuffer)
        var needed = 0
        CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sampleBuffer, bufferListSizeNeededOut: &needed,
            bufferListOut: nil, bufferListSize: 0, blockBufferAllocator: nil, blockBufferMemoryAllocator: nil,
            flags: 0, blockBufferOut: nil)
        guard frames > 0, needed > 0 else { return }
        let raw = UnsafeMutableRawPointer.allocate(byteCount: needed, alignment: 16)
        defer { raw.deallocate() }
        let list = raw.bindMemory(to: AudioBufferList.self, capacity: 1)
        var block: CMBlockBuffer?
        guard CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sampleBuffer, bufferListSizeNeededOut: nil,
            bufferListOut: list, bufferListSize: needed, blockBufferAllocator: nil, blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, blockBufferOut: &block) == noErr else { return }
        let buffers = UnsafeMutableAudioBufferListPointer(list)
        let channels = max(1, Int(asbd.mChannelsPerFrame))
        let isFloat = asbd.mFormatFlags & kAudioFormatFlagIsFloat != 0
        let planar = asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        // interleave to stereo 16-bit, whatever layout macOS delivers (usually planar float)
        func sample(_ frame: Int, _ channel: Int) -> Float {
            let c = min(channel, channels - 1)
            let buffer = planar ? buffers[min(c, buffers.count - 1)] : buffers[0]
            guard let data = buffer.mData else { return 0 }
            let index = planar ? frame : frame * channels + c
            if isFloat { return data.assumingMemoryBound(to: Float.self)[index] }
            return Float(data.assumingMemoryBound(to: Int16.self)[index]) / 32768
        }
        var out = [Int16](repeating: 0, count: frames * 2)
        for f in 0..<frames {
            for c in 0..<2 {
                out[f * 2 + c] = Int16(max(-1, min(1, sample(f, c))) * 32767).littleEndian
            }
        }
        out.withUnsafeBytes { stdout.write(Data($0)) }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        fail("sound capture stopped: \(error.localizedDescription)")
    }
}

do {
    let rate = args.count >= 2 ? Int(args[1]) ?? 48000 : 48000
    if #available(macOS 13.0, *) {
        let audio = AudioWriter()
        Task {
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                guard let display = content.displays.first else { fail("no display found") }
                let config = SCStreamConfiguration()
                config.capturesAudio = true
                config.excludesCurrentProcessAudio = true
                config.sampleRate = rate
                config.channelCount = 2
                // a picture is required, so ask for a tiny, rare one and ignore it
                config.width = 2
                config.height = 2
                config.minimumFrameInterval = CMTime(value: 1, timescale: 1)
                config.showsCursor = false
                let filter = SCContentFilter(display: display, excludingWindows: [])
                let stream = SCStream(filter: filter, configuration: config, delegate: audio)
                try stream.addStreamOutput(audio, type: .audio, sampleHandlerQueue: DispatchQueue(label: "wiiuu.audio"))
                try await stream.startCapture()
                keepAlive = stream
            } catch {
                fail("sound capture failed: \(error.localizedDescription) - is Screen Recording allowed for WII-UU?")
            }
        }
        dispatchMain()
    } else {
        fail("sound needs macOS 13 (Ventura) or newer")
    }
}
