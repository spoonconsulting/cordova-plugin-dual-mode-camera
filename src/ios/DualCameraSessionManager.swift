import Foundation
import AVFoundation

protocol DualCameraSessionManagerDelegate: AnyObject {
    func sessionManager(_ manager: DualCameraSessionManager, didOutput sampleBuffer: CMSampleBuffer, from output: AVCaptureOutput)
}

class DualCameraSessionManager: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    let session = AVCaptureMultiCamSession()
    private let queue = DispatchQueue(label: "dualMode.session.queue", qos: .userInitiated)
    private let stateQueue = DispatchQueue(label: "dualMode.session.state", qos: .userInitiated)
    private(set) var backInput: AVCaptureDeviceInput?
    private(set) var frontInput: AVCaptureDeviceInput?
    private(set) var backOutput = AVCaptureVideoDataOutput()
    private(set) var frontOutput = AVCaptureVideoDataOutput()
    private(set) var backVideoPort: AVCaptureInput.Port?
    private(set) var frontVideoPort: AVCaptureInput.Port?
    private(set) var audioOutput: AVCaptureAudioDataOutput?
    private(set) var audioInput: AVCaptureInput?
    private var videoRecorder: VideoRecorder?
    var videoMixer = VideoMixer()
    private var latestBackBuffer: CMSampleBuffer?
    private var latestFrontBuffer: CMSampleBuffer?
    private var _isConfiguring = false
    private var _isSetupComplete = false
    private var _isRecording = false
    private var isMixerReady = false
    private var hasAppendedFirstFrame = false
    var onFirstVideoFrame: (() -> Void)?
    weak var delegate: DualCameraSessionManagerDelegate?
    

    private func prepareMixer(with formatDesc: CMFormatDescription, orientation: UIDeviceOrientation?) {
        let orientationToUse = orientation ?? OrientationHelper.validDeviceOrientation()
        let isLandscape = orientationToUse.isLandscape
        let targetWidth: Int32 = isLandscape ? 1920 : 1080
        let targetHeight: Int32 = isLandscape ? 1080 : 1920
        self.videoMixer.prepare(with: formatDesc,
                                outputRetainedBufferCountHint: 6,
                                targetWidth: targetWidth,
                                targetHeight: targetHeight)
    }

    func setupSession(delegate: DualCameraSessionManagerDelegate, completion: @escaping (Bool) -> Void) {
        self.delegate = delegate
        
        self.isMixerReady = false
        
        queue.async { [weak self] in
            guard let self = self else { 
                DispatchQueue.main.async { completion(false) }
                return 
            }

            self.isConfiguring = true
            self.session.beginConfiguration()
            var setupSuccess = true

            defer { 
                self.session.commitConfiguration()
                self.isConfiguring = false
                self.isSetupComplete = setupSuccess
                DispatchQueue.main.async {
                    completion(setupSuccess)
                }
            }

            setupSuccess = self.setupBackCamera() && 
                          self.setupFrontCamera() && 
                          self.setupMicrophone()

            if setupSuccess {
                self.backOutput.setSampleBufferDelegate(self, queue: self.queue)
                self.frontOutput.setSampleBufferDelegate(self, queue: self.queue)
                if let audioOutput = self.audioOutput {
                    audioOutput.setSampleBufferDelegate(self, queue: self.queue)
                }
            }
        }
    }

    func startRecording(with recorder: VideoRecorder) {
        queue.async { [weak self] in
            guard let self = self else { return }

            guard self.isReady() && !self.isRecording else { return }

            self.videoRecorder = recorder
            self.isRecording = true
            self.hasAppendedFirstFrame = false
            self.videoMixer.lockOrientation()
            let isLandscape = UIDevice.current.orientation.isLandscape
            if isLandscape {
                self.videoMixer.pipFrame = CGRect(x: 0.03, y: 0.03, width: 0.25, height: 0.25)
            } else {
                self.videoMixer.pipFrame = CGRect(x: 0.05, y: 0.05, width: 0.3, height: 0.3)
            }
            
        }
    }
    
    func stopRecording() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.videoMixer.unlockOrientation()
            self.videoRecorder = nil
            self.isRecording = false
        }
    }

    func startSession() {
        queue.async { [weak self] in
            guard let self = self else { return }

            if self.isSetupComplete && !self.isConfiguring && !self.session.isRunning {
                self.session.startRunning()
            }
        }
    }

    func isReady() -> Bool {
        return isSetupComplete && !isConfiguring
    }

    func stopSession(completion: (() -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self = self else {
                DispatchQueue.main.async { completion?() }
                return
            }
      
            if self.isRecording {
                self.stopRecording()
            }
         
            if self.session.isRunning {
                self.session.stopRunning()
            }
            
            self.isMixerReady = false
            
            DispatchQueue.main.async {
                completion?()
            }
        }
    }

    private func setupBackCamera() -> Bool {
        guard let backCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let backInput = try? AVCaptureDeviceInput(device: backCamera),
              session.canAddInput(backInput) else {
            print("Cannot create/add back camera input")
            return false
        }

        configureCamera(backCamera, desiredWidth: 1920, desiredHeight: 1080)
        self.backInput = backInput
        session.addInputWithNoConnections(backInput)

        if let port = backInput.ports.first(where: { $0.mediaType == .video }) {
            self.backVideoPort = port
        }

        if session.canAddOutput(backOutput) {
            backOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)
            ]
            session.addOutputWithNoConnections(backOutput)

            if let port = self.backVideoPort {
                let connection = AVCaptureConnection(inputPorts: [port], output: backOutput)
                connection.videoOrientation = OrientationHelper.currentAVCaptureOrientation()
                session.addConnection(connection)
            } else {
                print("Back camera port missing for connection")
                return false
            }
        } else {
            print("Cannot add back video output")
            return false
        }
        return true
    }
    
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        
        if !self.isMixerReady && !self.isRecording && output == backOutput {
            if let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer) {
                self.isMixerReady = true
                prepareMixer(with: formatDesc, orientation: nil)
            }
        }
        
        if let videoRecorder = self.videoRecorder, self.isRecording {
            if output == backOutput {
                self.latestBackBuffer = sampleBuffer
            } else if output == frontOutput {
                self.latestFrontBuffer = sampleBuffer
            } else if output == audioOutput {
                videoRecorder.appendAudioBuffer(sampleBuffer)
                return
            }

            if self.videoMixer.inputFormatDescription == nil,
               let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer) {
                prepareMixer(with: formatDesc, orientation: self.videoMixer.lockedOrientation)
                return
            }

            guard let front = latestFrontBuffer, let back = latestBackBuffer else { 
                return 
            }

            guard let frontBuffer = CMSampleBufferGetImageBuffer(front),
                  let backBuffer = CMSampleBufferGetImageBuffer(back) else { 
                return 
            }

            if let merged = self.videoMixer.mix(fullScreenPixelBuffer: backBuffer, pipPixelBuffer: frontBuffer, fullScreenPixelBufferIsFrontCamera: false) {
                let backPts = CMSampleBufferGetPresentationTimeStamp(back)
                videoRecorder.appendVideoPixelBuffer(merged, withPresentationTime: backPts)
                
                if !self.hasAppendedFirstFrame {
                    self.hasAppendedFirstFrame = true
                    if let firstFrameHandler = self.onFirstVideoFrame {
                        self.onFirstVideoFrame = nil
                        DispatchQueue.main.async { firstFrameHandler() }
                    }
                }
                
                latestFrontBuffer = nil
                latestBackBuffer = nil
            } else {
            }
        }

        delegate?.sessionManager(self, didOutput: sampleBuffer, from: output)
    }

    private func setupFrontCamera() -> Bool {
        guard let frontCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
              let frontInput = try? AVCaptureDeviceInput(device: frontCamera),
              session.canAddInput(frontInput) else {
            print("Cannot create/add front camera input")
            return false
        }

        configureCamera(frontCamera, desiredWidth: 1920, desiredHeight: 1080)
        self.frontInput = frontInput
        session.addInputWithNoConnections(frontInput)

        if let port = frontInput.ports.first(where: { $0.mediaType == .video }) {
            self.frontVideoPort = port
        }

        if session.canAddOutput(frontOutput) {
            frontOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)
            ]
            session.addOutputWithNoConnections(frontOutput)

            if let port = self.frontVideoPort {
                let connection = AVCaptureConnection(inputPorts: [port], output: frontOutput)
                connection.videoOrientation = OrientationHelper.currentAVCaptureOrientation()
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = true
                session.addConnection(connection)
            } else {
                print("Front camera port missing for connection")
                return false
            }
        } else {
            print("Cannot add front video output")
            return false
        }
        return true
    }

    private func setupMicrophone() -> Bool {
        guard let mic = AVCaptureDevice.default(for: .audio),
              let micInput = try? AVCaptureDeviceInput(device: mic),
              session.canAddInput(micInput) else {
            print("Cannot create/add microphone input")
            return false
        }

        self.audioInput = micInput
        session.addInputWithNoConnections(micInput)

        let audioOutput = AVCaptureAudioDataOutput()
        if session.canAddOutput(audioOutput) {
            session.addOutputWithNoConnections(audioOutput)

            if let port = micInput.ports.first(where: { $0.mediaType == .audio }) {
                let audioConnection = AVCaptureConnection(inputPorts: [port], output: audioOutput)
                if session.canAddConnection(audioConnection) {
                    session.addConnection(audioConnection)
                } else {
                    print("Cannot add microphone connection")
                    return false
                }
            } else {
                print("Microphone port missing for connection")
                return false
            }

            self.audioOutput = audioOutput
        } else {
            print("Cannot add microphone output")
            return false
        }
        return true
    }

    private func configureCamera(_ device: AVCaptureDevice, desiredWidth: Int32, desiredHeight: Int32) {
        guard let matchingFormat = device.formats.first(where: {
            let dims = CMVideoFormatDescriptionGetDimensions($0.formatDescription)
            return dims.width == desiredWidth && dims.height == desiredHeight
        }) else {
            print("No matching format \(desiredWidth)x\(desiredHeight) for \(device.localizedName)")
            return
        }

        do {
            try device.lockForConfiguration()
            device.activeFormat = matchingFormat
            device.unlockForConfiguration()
        } catch {
            print("Failed to lock configuration for \(device.localizedName): \(error)")
        }
    }
    
    private(set) var isConfiguring: Bool {
        get { stateQueue.sync { _isConfiguring } }
        set { stateQueue.sync { _isConfiguring = newValue } }
    }
    
    private(set) var isSetupComplete: Bool {
        get { stateQueue.sync { _isSetupComplete } }
        set { stateQueue.sync { _isSetupComplete = newValue } }
    }
    
    private var isRecording: Bool {
        get { stateQueue.sync { _isRecording } }
        set { stateQueue.sync { _isRecording = newValue } }
    }
    
    var hasFirstFrame: Bool {
        stateQueue.sync { hasAppendedFirstFrame }
    }

    func notifyWhenFirstFrame(_ completion: @escaping () -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            if self.hasAppendedFirstFrame {
                DispatchQueue.main.async { completion() }
            } else {
                self.onFirstVideoFrame = completion
            }
        }
    }
}










