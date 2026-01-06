# Cordova Plugin Dual Camera Preview

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
DualCameraPreview.deviceSupportDualMode((value: unknown) => {
  console.log("Device support dual mode", value);
});
```

### enable(successCallback, errorCallback)

Starts dual mode
<br>

```javascript
DualCameraPreview.enable(() => {
  console.log("Dual mode enabled");
});
```

### capture(successCallback, errorCallback)

<info> Capture image in Dual mode </info><br>

```javascript
DualCameraPreview.capture((imageNativePath: string) => {
  console.log(imageNativePath);
});
```

### disable(successCallback, errorCallback)

<info> Stops dual mode</info><br>

```javascript
DualCameraPreview.disable(() => {
  console.log("Dual mode disabled");
});
```
