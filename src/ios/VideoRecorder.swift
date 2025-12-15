import Foundation
import AVFoundation
import UIKit

class VideoRecorder {
    private var assetWriter: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var startTime: CMTime?
    private var outputURL: URL?
    private var completionHandler: ((String, String?, Error?) -> Void)?
    private let writerQueue = DispatchQueue(label: "video.recorder.queue", qos: .userInteractive) // Higher priority
    private let stateQueue = DispatchQueue(label: "video.recorder.state", qos: .userInteractive)
    private var _isWriting = false

    private func resetState() {
        assetWriter = nil
        videoInput = nil
        audioInput = nil
        adaptor = nil
        startTime = nil
        outputURL = nil
        completionHandler = nil
    }

    private func makeWriter(at url: URL) throws -> AVAssetWriter {
        return try AVAssetWriter(outputURL: url, fileType: .mov)
    }

    private func videoDimensions(for orientation: UIDeviceOrientation) -> (width: Int32, height: Int32) {
        let isLandscape = orientation.isLandscape
        return (isLandscape ? 1920 : 1080, isLandscape ? 1080 : 1920)
    }

    private func addVideoInput(to writer: AVAssetWriter, width: Int32, height: Int32) -> Bool {
        let videoSettings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: 10_000_000,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
                AVVideoExpectedSourceFrameRateKey: 30,
                AVVideoMaxKeyFrameIntervalKey: 30
            ]
        ]

        let input = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        input.expectsMediaDataInRealTime = true
        input.performsMultiPassEncodingIfSupported = false

        let sourcePixelBufferAttributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height
        ]

        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: sourcePixelBufferAttributes)

        guard writer.canAdd(input) else { return false }
        writer.add(input)

        self.videoInput = input
        self.adaptor = adaptor
        return true
    }

    private func addAudioInput(to writer: AVAssetWriter) -> Bool {
        let audioSettings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVNumberOfChannelsKey: 1,
            AVSampleRateKey: 48000,
            AVEncoderBitRateKey: 96000
        ]

        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
        input.expectsMediaDataInRealTime = true

        guard writer.canAdd(input) else { return false }
        writer.add(input)

        self.audioInput = input
        return true
    }

    func startWriting(audioEnabled: Bool, recordingOrientation: UIDeviceOrientation? = nil, completion: @escaping (Error?) -> Void) {
        writerQueue.async { [weak self] in
            guard let self = self else {
                completion(NSError(domain: "VideoRecorder", code: 1000, userInfo: [NSLocalizedDescriptionKey: "VideoRecorder deallocated"]))
                return
            }

            guard !self.isWriting else {
                completion(NSError(domain: "VideoRecorder", code: 1001, userInfo: [NSLocalizedDescriptionKey: "Already writing"]))
                return
            }

            self.resetState()

            do {
                let outputDirectory = try FileManager.default.url(
                    for: .libraryDirectory,
                    in: .userDomainMask,
                    appropriateFor: nil,
                    create: true
                ).appendingPathComponent("NoCloud", isDirectory: true)

                try FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)

                let fileName = UUID().uuidString + ".mov"
                self.outputURL = outputDirectory.appendingPathComponent(fileName)
                let writer = try self.makeWriter(at: self.outputURL!)
                self.assetWriter = writer
                let orientationToUse = recordingOrientation ?? UIDevice.current.orientation
                let validOrientation: UIDeviceOrientation
                switch orientationToUse {
                case .portrait, .portraitUpsideDown, .landscapeLeft, .landscapeRight:
                    validOrientation = orientationToUse
                case .faceUp, .faceDown, .unknown:
                    validOrientation = .portrait
                @unknown default:
                    validOrientation = .portrait
                }
                let (videoWidth, videoHeight) = self.videoDimensions(for: validOrientation)

                guard self.addVideoInput(to: writer, width: videoWidth, height: videoHeight) else {
                    completion(NSError(domain: "VideoRecorder", code: 1002, userInfo: [NSLocalizedDescriptionKey: "Failed to add video input"]))
                    return
                }

                if audioEnabled {
                    guard self.addAudioInput(to: writer) else {
                        completion(NSError(domain: "VideoRecorder", code: 1002, userInfo: [NSLocalizedDescriptionKey: "Failed to add audio input"]))
                        return
                    }
                }
                
                
                
                guard writer.startWriting() else {
                    DispatchQueue.main.async {
                        completion(writer.error ?? NSError(domain: "VideoRecorder",
                                                           code: 1003,
                                                           userInfo: [NSLocalizedDescriptionKey: "Failed to start writing"]))
                    }
                    return
                }
                
                self.isWriting = true
                self.completionHandler = nil
                
                DispatchQueue.main.async {
                    completion(nil)
                }

            } catch {
                DispatchQueue.main.async {
                    completion(error)
                }
            }
        }
    }

    func appendVideoPixelBuffer(_ pixelBuffer: CVPixelBuffer, withPresentationTime presentationTime: CMTime) {
        writerQueue.async { [weak self] in
            guard let self = self else { 
                return 
            }
            
            
            guard self.isWriting else {
                return
            }
            
            guard let writer = self.assetWriter else {
                return
            }
            
            
            guard writer.status == .writing else {
                return
            }
            
            guard let vInput = self.videoInput, let adaptor = self.adaptor else {
                return
            }

            // Start session on first frame (writer.startWriting() was already called during setup)
            if self.startTime == nil {
                writer.startSession(atSourceTime: presentationTime)
                self.startTime = presentationTime
            }

            if vInput.isReadyForMoreMediaData {
                adaptor.append(pixelBuffer, withPresentationTime: presentationTime)
            }
        }
    }

    func appendAudioBuffer(_ sampleBuffer: CMSampleBuffer) {
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        writerQueue.async { [weak self] in
            guard let self = self else { 
                return 
            }
            
            
            guard self.isWriting else {
                return
            }
            
            guard let writer = self.assetWriter else {
                return
            }
            
            guard let aInput = self.audioInput else {
                return
            }
            

            // Wait for video to start the session (ensures timestamp sync)
            guard writer.status == .writing, self.startTime != nil else {
                return
            }

            // Append current buffer
            if aInput.isReadyForMoreMediaData {
                aInput.append(sampleBuffer)
            }
        }
    }

    func stopWriting(completion: @escaping (String, String?, Error?) -> Void) {
        writerQueue.async { [weak self] in
            guard let self = self else {
                completion("", nil, NSError(domain: "VideoRecorder", code: 1000, userInfo: [NSLocalizedDescriptionKey: "VideoRecorder deallocated"]))
                return
            }
            
            guard self.isWriting, let writer = self.assetWriter else {
                completion("", nil, NSError(domain: "VideoRecorder", code: 1003, userInfo: [NSLocalizedDescriptionKey: "Recording was not started"]))
                return
            }

            
            self.isWriting = false
            self.completionHandler = completion

            self.videoInput?.markAsFinished()
            self.audioInput?.markAsFinished()

            writer.finishWriting { [weak self] in
                guard let self = self else { 
                    return 
                }


                if let error = writer.error {
                    DispatchQueue.main.async {
                        self.completionHandler?("", nil, error)
                    }
                    return
                }

                guard let videoPath = self.outputURL?.path else {
                    DispatchQueue.main.async {
                        self.completionHandler?("", nil, NSError(domain: "VideoRecorder", code: 1004, userInfo: [NSLocalizedDescriptionKey: "No video file path"]))
                    }
                    return
                }

                
                self.generateThumbnail(from: URL(fileURLWithPath: videoPath)) { thumbnailPath in
                    DispatchQueue.main.async {
                        self.completionHandler?(videoPath, thumbnailPath, nil)
                        self.resetState()
                    }
                }
            }
        }
    }

    private func generateThumbnail(from url: URL, completion: @escaping (String?) -> Void) {
        let asset = AVAsset(url: url)
        let imageGenerator = AVAssetImageGenerator(asset: asset)
        imageGenerator.appliesPreferredTrackTransform = true
        let time = CMTime(seconds: 1.0, preferredTimescale: 600)

        DispatchQueue.global().async {
            do {
                let cgImage = try imageGenerator.copyCGImage(at: time, actualTime: nil)
                let uiImage = UIImage(cgImage: cgImage)
                if let data = uiImage.jpegData(compressionQuality: 0.8) {
                    let thumbName = UUID().uuidString + "video_thumb_.jpg"
                    let dir = try FileManager.default.url(for: .libraryDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                        .appendingPathComponent("NoCloud", isDirectory: true)

                    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                    let fileURL = dir.appendingPathComponent(thumbName)
                    try data.write(to: fileURL)
                    completion(fileURL.path)
                } else {
                    completion(nil)
                }
            } catch {
                print("Thumbnail generation failed: \(error)")
                completion(nil)
            }
        }
    }

    private var isWriting: Bool {
        get { stateQueue.sync { _isWriting } }
        set { stateQueue.sync { _isWriting = newValue } }
    }
}
