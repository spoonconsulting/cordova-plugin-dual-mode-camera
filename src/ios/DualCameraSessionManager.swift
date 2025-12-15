import Foundation
import AVFoundation

protocol DualCameraSessionManagerDelegate: AnyObject {
    func sessionManager(_ manager: DualCameraSessionManager, didOutput sampleBuffer: CMSampleBuffer, from output: AVCaptureOutput)
}

class DualCameraSessionManager: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    let session = AVCaptureMultiCamSession()
    private let queue = DispatchQueue(label: "dualMode.session.queue", qos: .userInitiated)
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
    private let stateLock = NSLock()
    private var _isConfiguring = false
    private var _isSetupComplete = false
    private var _isRecording = false
    private var _mixerReady = false
    weak var delegate: DualCameraSessionManagerDelegate?

    func setupSession(delegate: DualCameraSessionManagerDelegate, completion: @escaping (Bool) -> Void) {
        self.delegate = delegate
        
        // Reset VideoMixer to ensure clean state
        // self.videoMixer = VideoMixer()
        self._mixerReady = false
        
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
            guard let self = self else { 
                return 
            }

            guard self.isReady() && !self.isRecording else {
                return
            }

            self.videoRecorder = recorder
            self.isRecording = true
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
            guard let self = self else { 
                return 
            }
            self.videoMixer.unlockOrientation()
            self.videoRecorder = nil
            self.isRecording = false
        }
    }

    func startSession() {
        queue.async { [weak self] in
            guard let self = self else { 
                return 
            }

            if self.isSetupComplete && !self.isConfiguring && !self.session.isRunning {
                self.session.startRunning()
            }
        }
    }

    func isReady() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return _isSetupComplete && !_isConfiguring
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
            
            // Reset mixer flag so it prepares with new orientation on restart
            self._mixerReady = false
            
            // Notify completion on main thread
            DispatchQueue.main.async {
                completion?()
            }
        }
    }

    private func setupBackCamera() -> Bool {
        guard let backCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
          let backInput = try? AVCaptureDeviceInput(device: backCamera),
          session.canAddInput(backInput) else {
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
                connection.videoOrientation = getCurrentVideoOrientation()
                session.addConnection(connection)
            }
        } else {
            return false
        }
        return true
    }
    
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        
        // Pre-warm VideoMixer on first frame (during preview, before recording)
        if !self._mixerReady && !self.isRecording && output == backOutput {
            if let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer) {
                self._mixerReady = true
                
                let currentOrientation = UIDevice.current.orientation
                let validOrientation: UIDeviceOrientation
                switch currentOrientation {
                case .portrait, .portraitUpsideDown, .landscapeLeft, .landscapeRight:
                    validOrientation = currentOrientation
                case .faceUp, .faceDown, .unknown:
                    validOrientation = .portrait
                @unknown default:
                    validOrientation = .portrait
                }
                
                let isLandscape = validOrientation.isLandscape
                let targetWidth: Int32 = isLandscape ? 1920 : 1080
                let targetHeight: Int32 = isLandscape ? 1080 : 1920
                
                // Prepare VideoMixer on first frame
                self.videoMixer.prepare(with: formatDesc, outputRetainedBufferCountHint: 6, targetWidth: targetWidth, targetHeight: targetHeight)
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

            // VideoMixer should already be pre-warmed from preview
            // If not (edge case), it will prepare now but recording might have brief lag
            if self.videoMixer.inputFormatDescription == nil {
                if let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer) {
                    let orientationToUse = self.videoMixer.lockedOrientation ?? UIDevice.current.orientation
                    let validOrientation: UIDeviceOrientation
                    switch orientationToUse {
                    case .portrait, .portraitUpsideDown, .landscapeLeft, .landscapeRight:
                        validOrientation = orientationToUse
                    case .faceUp, .faceDown, .unknown:
                        validOrientation = .portrait
                    @unknown default:
                        validOrientation = .portrait
                    }
                    
                    let isLandscape = validOrientation.isLandscape
                    let targetWidth: Int32 = isLandscape ? 1920 : 1080
                    let targetHeight: Int32 = isLandscape ? 1080 : 1920
                    
                    self.videoMixer.prepare(with: formatDesc, outputRetainedBufferCountHint: 6, targetWidth: targetWidth, targetHeight: targetHeight)
                }
                return // Skip first frame
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
                connection.videoOrientation = getCurrentVideoOrientation()
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = true
                session.addConnection(connection)
            }
        } else {
            return false
        }
        return true
    }

    private func setupMicrophone() -> Bool {
        guard let mic = AVCaptureDevice.default(for: .audio),
              let micInput = try? AVCaptureDeviceInput(device: mic),
              session.canAddInput(micInput) else {
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
                }
            }

            self.audioOutput = audioOutput
        } else {
            return false
        }
        return true
    }

    private func configureCamera(_ device: AVCaptureDevice, desiredWidth: Int32, desiredHeight: Int32) {
        for format in device.formats {
            let description = format.formatDescription
            let dimensions = CMVideoFormatDescriptionGetDimensions(description)
            if dimensions.width == desiredWidth && dimensions.height == desiredHeight {
                do {
                    try device.lockForConfiguration()
                    device.activeFormat = format
                    device.unlockForConfiguration()
                    print("Set \(device.localizedName) resolution to \(desiredWidth)x\(desiredHeight)")
                    break
                } catch {
                    print("Error locking configuration for \(device.localizedName): \(error)")
                }
            }
        }
    }
    
    private func getCurrentVideoOrientation() -> AVCaptureVideoOrientation {
        let orientation = UIDevice.current.orientation
        switch orientation {
        case .portrait:
            return .portrait
        case .portraitUpsideDown:
            return .portraitUpsideDown
        case .landscapeLeft:
            return .landscapeRight
        case .landscapeRight:
            return .landscapeLeft
        default:
            return .portrait
        }
    }

     private(set) var isConfiguring: Bool {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _isConfiguring
        }

        set {
            stateLock.lock()
            defer { stateLock.unlock() }
            _isConfiguring = newValue
        }
    }
    
    private(set) var isSetupComplete: Bool {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _isSetupComplete
        }

        set {
            stateLock.lock()
            defer { stateLock.unlock() }
            _isSetupComplete = newValue
        }
    }
    
    private var isRecording: Bool {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _isRecording
        }

        set {
            stateLock.lock()
            defer { stateLock.unlock() }
            _isRecording = newValue
        }
    }
}
