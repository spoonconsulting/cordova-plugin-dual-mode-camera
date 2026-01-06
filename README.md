Cordova Plugin Dual Camera Preview
====================

Cordova plugin that allows dual mode camera

# Installation

```
cordova plugin add https://github.com/spoonconsulting/cordova-plugin-dual-mode-camera.git

```

# Methods

### deviceSupportDualMode(successCallback, errorCallback)
Check if device support dual mode
<br>

```javascript
DualCameraPreview.deviceSupportDualMode = function (onSuccess, onError) {
  exec(onSuccess, onError, PLUGIN_NAME, "deviceSupportDualMode", []);
};
```

### enable(successCallback, errorCallback)

Starts dual mode 
<br>

```javascript
DualCameraPreview.enable = function (onSuccess, onError) {
  exec(onSuccess, onError, PLUGIN_NAME, "enable", []);
};
```

### capture(successCallback, errorCallback)

<info> Capture image in Dual mode </info><br>

```javascript
DualCameraPreview.capture = function (onSuccess, onError) {
  exec(onSuccess, onError, PLUGIN_NAME, "capture", []);
};
```

### disable(successCallback, errorCallback)

<info> Stops dual mode</info><br>

```javascript
DualCameraPreview.capture = function (onSuccess, onError) {
  exec(onSuccess, onError, PLUGIN_NAME, "capture", []);
};
```