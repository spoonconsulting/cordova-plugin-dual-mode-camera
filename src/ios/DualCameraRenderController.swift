import UIKit
import AVFoundation

class DualCameraRenderController {
    private var backPreviewLayer: AVCaptureVideoPreviewLayer?
    private var frontPreviewLayer: AVCaptureVideoPreviewLayer?
    private var pipView: UIView?
    private var containerView: UIView?
    private var session: AVCaptureMultiCamSession?
    private var sessionManager: DualCameraSessionManager?
    private weak var dualCameraPreview: DualCameraPreview?

    func setupPreview(on view: UIView, session: AVCaptureMultiCamSession, sessionManager: DualCameraSessionManager, dualCameraPreview: DualCameraPreview) {
        self.containerView = view
        self.session = session
        self.sessionManager = sessionManager
        self.dualCameraPreview = dualCameraPreview
        session.beginConfiguration()
        setupBackPreviewLayer(on: view, session: session)
        setupPiPView(on: view)
        setupFrontPreviewLayer(session: session)
        session.commitConfiguration()
        updatePreviewForCurrentOrientation()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(orientationChanged),
            name: UIDevice.orientationDidChangeNotification,
            object: nil
        )
    }

    func teardownPreview() {
        NotificationCenter.default.removeObserver(self)
        backPreviewLayer?.removeFromSuperlayer()
        backPreviewLayer = nil
        frontPreviewLayer?.removeFromSuperlayer()
        frontPreviewLayer = nil
        pipView?.removeFromSuperview()
        pipView = nil
        containerView = nil
        session = nil
    }

    @objc private func orientationChanged() {
        if let dualCameraPreview = dualCameraPreview, dualCameraPreview.isCurrentlyRecording {
            return
        }
        
        guard let containerView = containerView,
              let session = session,
              let sessionManager = sessionManager,
              let dualCameraPreview = dualCameraPreview else { 
            return 
        }
        
        // Re-enable camera with new orientation
        DispatchQueue.main.async {
            self.teardownPreview()
            
            // Stop session and wait for completion before re-setup
            sessionManager.stopSession { [weak self] in
                guard let self = self else { return }
                
                // Now that session is fully stopped, re-setup with correct orientation
                self.setupPreview(on: containerView, session: session, sessionManager: sessionManager, dualCameraPreview: dualCameraPreview)
                self.updatePreviewForCurrentOrientation()
                sessionManager.startSession()
            }
        }
    }
    
    private func updatePreviewForCurrentOrientation() {
        guard let containerView = containerView,
              let pipView = pipView,
              let frontPreviewLayer = frontPreviewLayer,
              let backPreviewLayer = backPreviewLayer,
              let sessionManager = sessionManager else { 
            return 
        }
        
        let orientation = UIDevice.current.orientation
        let isLandscape = orientation == .landscapeLeft || orientation == .landscapeRight
        
        // Update PIP frame based on orientation
        pipView.frame = isLandscape 
            ? CGRect(x: 20, y: 15, width: 240, height: 160)
            : CGRect(x: 16, y: 60, width: 160, height: 240)
        
        // Update preview layer frames
        frontPreviewLayer.frame = pipView.bounds
        backPreviewLayer.frame = containerView.bounds
        
        // Update connection orientations for both preview AND capture
        let videoOrientation = getVideoOrientation()
        
        // Update preview layer connections
        if let frontConnection = frontPreviewLayer.connection {
            if frontConnection.isVideoOrientationSupported {
                frontConnection.videoOrientation = videoOrientation
            }
        }
        
        if let backConnection = backPreviewLayer.connection {
            if backConnection.isVideoOrientationSupported {
                backConnection.videoOrientation = videoOrientation
            }
        }
        
        // Update capture output connections (for recording)
        if let backOutputConnection = sessionManager.backOutput.connection(with: .video) {
            if backOutputConnection.isVideoOrientationSupported {
                backOutputConnection.videoOrientation = videoOrientation
            }
        }
        
        if let frontOutputConnection = sessionManager.frontOutput.connection(with: .video) {
            if frontOutputConnection.isVideoOrientationSupported {
                frontOutputConnection.videoOrientation = videoOrientation
            }
        }
    }

    private func setupBackPreviewLayer(on view: UIView, session: AVCaptureMultiCamSession) {
        // Use WithNoConnections for MultiCamSession - we'll create connections manually
        backPreviewLayer = AVCaptureVideoPreviewLayer(sessionWithNoConnection: session)
        backPreviewLayer?.videoGravity = .resizeAspectFill
        backPreviewLayer?.frame = view.bounds

        // Manually create connection between back camera input and preview layer
        if let backLayer = backPreviewLayer,
           let backInput = sessionManager?.backInput,
           let backPort = sessionManager?.backVideoPort {
            
            let connection = AVCaptureConnection(inputPort: backPort, videoPreviewLayer: backLayer)
            if session.canAddConnection(connection) {
                session.addConnection(connection)
                connection.videoOrientation = getVideoOrientation()
            }
            
            if let webViewLayer = view.subviews.first(where: { $0 is WKWebView || $0 is UIWebView })?.layer {
                view.layer.insertSublayer(backLayer, below: webViewLayer)
            } else {
                view.layer.insertSublayer(backLayer, at: 0)
            }
        }
    }

    private func setupPiPView(on view: UIView) {
        let orientation = UIDevice.current.orientation
        let isLandscape = orientation == .landscapeLeft || orientation == .landscapeRight
        
        // Create PIP view with correct frame based on orientation
        let pipFrame = isLandscape 
            ? CGRect(x: 20, y: 15, width: 240, height: 160)  // Landscape
            : CGRect(x: 16, y: 60, width: 160, height: 240)  // Portrait
        
        let pipView = UIView(frame: pipFrame)
        self.pipView = pipView
        pipView.layer.cornerRadius = 12
        pipView.clipsToBounds = true
        pipView.backgroundColor = .black

        if let webView = view.subviews.first(where: { $0 is WKWebView || $0 is UIWebView }) {
            view.insertSubview(pipView, belowSubview: webView)
        } else {
            view.addSubview(pipView)
        }
    }

    private func setupFrontPreviewLayer(session: AVCaptureMultiCamSession) {
        guard let pipView = self.pipView else { return }

        // Use WithNoConnections for MultiCamSession - we'll create connections manually
        frontPreviewLayer = AVCaptureVideoPreviewLayer(sessionWithNoConnection: session)
        frontPreviewLayer?.videoGravity = .resizeAspectFill
        frontPreviewLayer?.frame = pipView.bounds

        // Manually create connection between front camera input and preview layer
        if let frontLayer = frontPreviewLayer,
           let frontInput = sessionManager?.frontInput,
           let frontPort = sessionManager?.frontVideoPort {
            
            let connection = AVCaptureConnection(inputPort: frontPort, videoPreviewLayer: frontLayer)
            if session.canAddConnection(connection) {
                session.addConnection(connection)
                connection.videoOrientation = getVideoOrientation()
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = true  // Mirror front camera
            }
            
            pipView.layer.addSublayer(frontLayer)
        }
    }
    
    private func getVideoOrientation() -> AVCaptureVideoOrientation {
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
}
