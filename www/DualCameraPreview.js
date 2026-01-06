var exec = require("cordova/exec");
var PLUGIN_NAME = "DualCameraPreview";
var DualCameraPreview = function () {};

DualCameraPreview.videoInitialized = false;
DualCameraPreview.videoCallback = null;

DualCameraPreview.deviceSupportDualMode = function (onSuccess, onError) {
  exec(onSuccess, onError, PLUGIN_NAME, "deviceSupportDualMode", []);
};

DualCameraPreview.enable = function (onSuccess, onError) {
  exec(onSuccess, onError, PLUGIN_NAME, "enable", []);
};

DualCameraPreview.capture = function (onSuccess, onError) {
  exec(onSuccess, onError, PLUGIN_NAME, "capture", []);
};

DualCameraPreview.disable = function (onSuccess, onError) {
  exec(onSuccess, onError, PLUGIN_NAME, "disable", []);
};

DualCameraPreview.initVideoCallback = function (onSuccess, onError, callback) {
  this.videoCallback = callback;
  exec(
      (info) => {
        if (info.videoCallbackInitialized) {
          DualCameraPreview.videoInitialized = true;
          onSuccess();
        }
        this.videoCallback(info);
      } ,
      onError,
      PLUGIN_NAME,
      "initVideoCallback",
      []
  );
}

DualCameraPreview.startVideoCapture = function (options, onSuccess, onError) {
  if (!DualCameraPreview.videoCallback) {
    console.error("Call initVideoCallback first");
    onError("Call initVideoCallback first");
    return;
  }

  if (!DualCameraPreview.videoInitialized) {
    console.error("videoCallback not initialized");
    onError("videoCallback not initialized");
    return;
  }
  
  options = options || {};
  options.recordWithAudio = options.recordWithAudio != null ? options.recordWithAudio : true;
  options.videoDurationMs = options.videoDurationMs != null ? options.videoDurationMs : 3000;
  exec(onSuccess, onError, PLUGIN_NAME, "startVideoCapture", [options]);
};

DualCameraPreview.stopVideoCapture = function (onSuccess, onError) {
  exec(onSuccess, onError, PLUGIN_NAME, "stopVideoCapture");
};

module.exports = DualCameraPreview;
