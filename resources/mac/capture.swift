// WII-UU screen capture helper for macOS, built on ScreenCaptureKit (macOS 12.3+).
//
// The GPU crops and scales the captured area to the phone's size and macOS's own JPEG encoder
// compresses it, so this is far cheaper than capturing the whole Retina screen in Java.
//
// usage:  wiiuu-capture <x> <y> <width> <height> <maxWidth> <fps> <quality 0..1>
//         (area in points, global screen coordinates, top-left origin)
// output: on stdout, for every new frame: 4-byte big-endian length, then the JPEG bytes.
//
// WII-UU compiles this itself with the Xcode Command Line Tools (xcrun swiftc) the first time.
import CoreImage
import CoreMedia
import CoreVideo
import Foundation
import ScreenCaptureKit

signal(SIGPIPE, SIG_DFL)          // WII-UU stops reading -> exit

func fail(_ message: String) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(1)
}

let args = CommandLine.arguments
guard args.count >= 8,
      let areaX = Double(args[1]), let areaY = Double(args[2]),
      let areaW = Double(args[3]), let areaH = Double(args[4]),
      let maxWidth = Double(args[5]), let fps = Int32(args[6]), let quality = Double(args[7]),
      areaW > 0, areaH > 0, fps > 0 else {
    fail("usage: wiiuu-capture x y width height maxWidth fps quality")
}

final class FrameWriter: NSObject, SCStreamOutput, SCStreamDelegate {
    private let context = CIContext(options: [.useSoftwareRenderer: false])
    private let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    private let quality: Double
    private let stdout = FileHandle.standardOutput

    init(quality: Double) {
        self.quality = quality
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        // only complete frames carry a picture (idle frames mean "nothing changed")
        guard type == .screen, sampleBuffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
                  as? [[SCStreamFrameInfo: Any]],
              let rawStatus = attachments.first?[.status] as? Int,
              let status = SCFrameStatus(rawValue: rawStatus), status == .complete,
              let pixels = sampleBuffer.imageBuffer else { return }
        let image = CIImage(cvPixelBuffer: pixels)
        let options = [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: quality]
        guard let jpeg = context.jpegRepresentation(of: image, colorSpace: colorSpace, options: options) else { return }
        var length = UInt32(jpeg.count).bigEndian
        var frame = Data(bytes: &length, count: 4)
        frame.append(jpeg)
        stdout.write(frame)
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        fail("capture stopped: \(error.localizedDescription)")
    }
}

let writer = FrameWriter(quality: quality)
var running: SCStream?

Task {
    do {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        let area = CGRect(x: areaX, y: areaY, width: areaW, height: areaH)
        guard let display = content.displays.first(where: { $0.frame.intersects(area) }) ?? content.displays.first else {
            fail("no display found")
        }
        let config = SCStreamConfiguration()
        // area relative to its display, in points; output scaled on the GPU to at most maxWidth pixels wide
        config.sourceRect = CGRect(x: areaX - display.frame.minX, y: areaY - display.frame.minY, width: areaW, height: areaH)
        let scale = min(1.0, maxWidth / areaW)
        config.width = max(2, Int((areaW * scale).rounded()) & ~1)
        config.height = max(2, Int((areaH * scale).rounded()) & ~1)
        config.minimumFrameInterval = CMTime(value: 1, timescale: fps)
        config.pixelFormat = kCVPixelFormatType_32BGRA
        config.showsCursor = false
        config.queueDepth = 3
        let filter = SCContentFilter(display: display, excludingWindows: [])
        let stream = SCStream(filter: filter, configuration: config, delegate: writer)
        try stream.addStreamOutput(writer, type: .screen, sampleHandlerQueue: DispatchQueue(label: "wiiuu.capture"))
        try await stream.startCapture()
        running = stream
    } catch {
        fail("capture failed: \(error.localizedDescription) - is Screen Recording allowed for WII-UU?")
    }
}

dispatchMain()
