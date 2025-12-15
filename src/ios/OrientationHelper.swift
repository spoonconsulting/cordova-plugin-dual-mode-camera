import UIKit
import AVFoundation

enum OrientationHelper {
    /// Returns a valid device orientation (defaults to portrait for unknown/faceUp/faceDown).
    static func validDeviceOrientation(from orientation: UIDeviceOrientation = UIDevice.current.orientation) -> UIDeviceOrientation {
        switch orientation {
        case .portrait, .portraitUpsideDown, .landscapeLeft, .landscapeRight:
            return orientation
        case .faceUp, .faceDown, .unknown:
            return .portrait
        @unknown default:
            return .portrait
        }
    }

    /// Maps device orientation to AVCaptureVideoOrientation (handles camera coordinate system).
    static func currentAVCaptureOrientation(from orientation: UIDeviceOrientation = UIDevice.current.orientation) -> AVCaptureVideoOrientation {
        switch orientation {
        case .portrait:
            return .portrait
        case .portraitUpsideDown:
            return .portraitUpsideDown
        case .landscapeLeft:
            // Device home button on the right => video orientation is landscapeRight
            return .landscapeRight
        case .landscapeRight:
            // Device home button on the left => video orientation is landscapeLeft
            return .landscapeLeft
        default:
            return .portrait
        }
    }

    /// Returns the PiP frame for the given orientation.
    static func pipFrame(for orientation: UIDeviceOrientation) -> CGRect {
        let isLandscape = orientation.isLandscape
        return isLandscape
            ? CGRect(x: 20, y: 15, width: 240, height: 160)
            : CGRect(x: 16, y: 60, width: 160, height: 240)
    }
}

