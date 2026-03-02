import UIKit
import AVFoundation

class DualCameraRenderController {
    private var backPreviewLayer: AVCaptureVideoPreviewLayer?
    private var frontPreviewLayer: AVCaptureVideoPreviewLayer?
    private var backPreviewConnection: AVCaptureConnection?
    private var frontPreviewConnection: AVCaptureConnection?
    private var pipView: UIView?
    private var containerView: UIView?
    private var session: AVCaptureMultiCamSession?
    private var sessionManager: DualCameraSessionManager?
    private weak var dualCameraPreview: DualCameraPreview?
    private var boundsObservation: NSKeyValueObservation?

    func setupPreview(on view: UIView, session: AVCaptureMultiCamSession, sessionManager: DualCameraSessionManager, dualCameraPreview: DualCameraPreview, completion: (() -> Void)? = nil) {
        self.containerView = view
        self.session = session
        self.sessionManager = sessionManager
        self.dualCameraPreview = dualCameraPreview
        
        view.layoutIfNeeded()
        session.beginConfiguration()
        setupBackPreviewLayer(on: view, session: session)
        setupPiPView(on: view)
        setupFrontPreviewLayer(session: session)
        session.commitConfiguration()
        
        boundsObservation = view.observe(\.bounds, options: [.new]) { [weak self] _, _ in
            self?.updateLayerFrames()
        }
        
        DispatchQueue.main.async { [weak self] in
            self?.updateLayerFrames()
            completion?()
        }
    }
    
    func updateLayerFrames() {
        guard let containerView = containerView else { return }
        
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        backPreviewLayer?.frame = containerView.bounds
        if let pipView = pipView {
            frontPreviewLayer?.frame = pipView.bounds
        }
        
        CATransaction.commit()
    }

    func teardownPreview() {
        boundsObservation?.invalidate()
        boundsObservation = nil
        
        backPreviewConnection = nil
        frontPreviewConnection = nil
        backPreviewLayer?.removeFromSuperlayer()
        backPreviewLayer = nil
        frontPreviewLayer?.removeFromSuperlayer()
        frontPreviewLayer = nil
        pipView?.removeFromSuperview()
        pipView = nil
        containerView = nil
        session = nil
    }

    private func setupBackPreviewLayer(on view: UIView, session: AVCaptureMultiCamSession) {
        backPreviewLayer = AVCaptureVideoPreviewLayer(sessionWithNoConnection: session)
        backPreviewLayer?.videoGravity = .resizeAspectFill
        backPreviewLayer?.frame = view.bounds

        if let backLayer = backPreviewLayer,
           let backInput = sessionManager?.backInput,
           let backPort = sessionManager?.backVideoPort {
            
            let connection = AVCaptureConnection(inputPort: backPort, videoPreviewLayer: backLayer)
            if session.canAddConnection(connection) {
                session.addConnection(connection)
                connection.videoOrientation = .portrait
                backPreviewConnection = connection
            }
            
            if let webViewLayer = view.subviews.first(where: { $0 is WKWebView || $0 is UIWebView })?.layer {
                view.layer.insertSublayer(backLayer, below: webViewLayer)
            } else {
                view.layer.insertSublayer(backLayer, at: 0)
            }
        }
    }

    private func setupPiPView(on view: UIView) {
        let pipView = UIView(frame: CGRect(x: 16, y: 60, width: 160, height: 240))
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

        frontPreviewLayer = AVCaptureVideoPreviewLayer(sessionWithNoConnection: session)
        frontPreviewLayer?.videoGravity = .resizeAspectFill
        frontPreviewLayer?.frame = pipView.bounds

        if let frontLayer = frontPreviewLayer,
           let frontInput = sessionManager?.frontInput,
           let frontPort = sessionManager?.frontVideoPort {
            
            let connection = AVCaptureConnection(inputPort: frontPort, videoPreviewLayer: frontLayer)
            if session.canAddConnection(connection) {
                session.addConnection(connection)
                connection.videoOrientation = .portrait
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = true
                frontPreviewConnection = connection
            }
            
            pipView.layer.addSublayer(frontLayer)
        }
    }
}
