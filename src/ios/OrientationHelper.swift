import UIKit
import AVFoundation

class OrientationHelper {
    static let shared = OrientationHelper()
    
    private var isTrackingOrientation = false
    private var _currentDeviceOrientation: UIDeviceOrientation = .portrait
    private let orientationQueue = DispatchQueue(label: "orientation.helper.queue")
    
    var currentDeviceOrientation: UIDeviceOrientation {
        orientationQueue.sync {
            let orientation = _currentDeviceOrientation
            switch orientation {
            case .portrait, .landscapeLeft, .landscapeRight:
                return orientation
            case .portraitUpsideDown, .faceUp, .faceDown, .unknown:
                return .portrait
            @unknown default:
                return .portrait
            }
        }
    }
    
    private init() {}
    
    func startTrackingOrientation() {
        guard !isTrackingOrientation else { return }
        
        isTrackingOrientation = true
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(deviceOrientationDidChange),
            name: UIDevice.orientationDidChangeNotification,
            object: nil
        )
        
        updateCurrentOrientation()
    }
    
    func stopTrackingOrientation() {
        guard isTrackingOrientation else { return }
        
        isTrackingOrientation = false
        UIDevice.current.endGeneratingDeviceOrientationNotifications()
        NotificationCenter.default.removeObserver(
            self,
            name: UIDevice.orientationDidChangeNotification,
            object: nil
        )
    }
    
    @objc private func deviceOrientationDidChange() {
        updateCurrentOrientation()
    }
    
    private func updateCurrentOrientation() {
        let newOrientation = UIDevice.current.orientation
        orientationQueue.sync {
            switch newOrientation {
            case .portrait, .portraitUpsideDown, .landscapeLeft, .landscapeRight:
                _currentDeviceOrientation = newOrientation
            default:
                break
            }
        }
    }
    
    /// This transform tells the player how to rotate the video for correct playback.
    func videoTransform(for deviceOrientation: UIDeviceOrientation) -> CGAffineTransform {
        switch deviceOrientation {
        case .landscapeRight:
            // Device rotated right (home button on right), video needs 90° clockwise rotation
            return CGAffineTransform(rotationAngle: .pi / 2)
        case .landscapeLeft:
            // Device rotated left (home button on left), video needs 90° counter-clockwise rotation
            return CGAffineTransform(rotationAngle: -.pi / 2)
        case .portraitUpsideDown:
            // Device upside down, video needs 180° rotation
            return CGAffineTransform(rotationAngle: .pi)
        case .portrait, .faceUp, .faceDown, .unknown:
            // Portrait or flat - no rotation needed
            return .identity
        @unknown default:
            return .identity
        }
    }
    
    func currentVideoTransform() -> CGAffineTransform {
        return videoTransform(for: currentDeviceOrientation)
    }
    
    func captureVideoOrientation(from deviceOrientation: UIDeviceOrientation) -> AVCaptureVideoOrientation {
        switch deviceOrientation {
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
    
    static func currentAVCaptureOrientation() -> AVCaptureVideoOrientation {
        return shared.captureVideoOrientation(from: shared.currentDeviceOrientation)
    }
    
    /// Get angle offset from portrait for a given video orientation and camera position
    func angleOffsetFromPortrait(for orientation: AVCaptureVideoOrientation, position: AVCaptureDevice.Position) -> Double {
        switch orientation {
        case .portrait:
            return position == .front ? .pi : 0
        case .portraitUpsideDown:
            return position == .front ? 0 : .pi
        case .landscapeRight:
            return -.pi / 2.0
        case .landscapeLeft:
            return .pi / 2.0
        @unknown default:
            return 0
        }
    }
    
    
    var validDeviceOrientation: UIDeviceOrientation {
        return currentDeviceOrientation
    }
    
    static func validDeviceOrientation() -> UIDeviceOrientation {
        return shared.currentDeviceOrientation
    }
    
    func imageRotationDegrees(for deviceOrientation: UIDeviceOrientation) -> CGFloat {
        switch deviceOrientation {
        case .landscapeLeft:
            return 180
        case .landscapeRight:
            return 0
        case .portraitUpsideDown:
            return -90
        case .portrait, .faceUp, .faceDown, .unknown:
            return -90
        @unknown default:
            return -90
        }
    }
    
    func rotateImage(_ image: UIImage, for deviceOrientation: UIDeviceOrientation) -> UIImage {
        let degrees = imageRotationDegrees(for: deviceOrientation)
        return rotateImage(image, byDegrees: degrees)
    }
    
    func rotateImage(_ image: UIImage, byDegrees degrees: CGFloat) -> UIImage {
        guard degrees != 0 else { return image }
        
        let radians = degrees * .pi / 180
        
        var newSize = CGRect(origin: .zero, size: image.size)
            .applying(CGAffineTransform(rotationAngle: radians))
            .size
        newSize.width = floor(newSize.width)
        newSize.height = floor(newSize.height)
        
        UIGraphicsBeginImageContextWithOptions(newSize, false, image.scale)
        guard let context = UIGraphicsGetCurrentContext() else { return image }
        
        context.translateBy(x: newSize.width / 2, y: newSize.height / 2)
        context.rotate(by: radians)
        image.draw(in: CGRect(
            x: -image.size.width / 2,
            y: -image.size.height / 2,
            width: image.size.width,
            height: image.size.height
        ))
        
        let rotatedImage = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        
        return rotatedImage ?? image
    }
    
    func imageOrientation(for videoOrientation: AVCaptureVideoOrientation) -> UIImage.Orientation {
        switch videoOrientation {
        case .portrait:
            return .up
        case .portraitUpsideDown:
            return .down
        case .landscapeRight:
            return .right
        case .landscapeLeft:
            return .left
        @unknown default:
            return .up
        }
    }
    
    func imageOrientationForCapture(connection: AVCaptureConnection?) -> UIImage.Orientation {
        if let connection = connection {
            return imageOrientation(for: connection.videoOrientation)
        }
        return .right
    }
}
