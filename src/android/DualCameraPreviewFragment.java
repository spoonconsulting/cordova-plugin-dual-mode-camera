package com.spoon.dualcamera;

import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.VideoRecordEvent;
import java.io.File;

import com.google.common.util.concurrent.ListenableFuture;

import org.apache.cordova.CallbackContext;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import androidx.exifinterface.media.ExifInterface;
import android.graphics.Matrix;
import androidx.annotation.OptIn;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import java.util.Collections;

public class DualCameraPreviewFragment extends Fragment {
    private PreviewView backPreviewView;
    private PreviewView frontPreviewView;
    private ProcessCameraProvider cameraProvider;
    private ConcurrentCamera concurrentCamera;
    private ImageCapture backImageCapture;
    private ImageCapture frontImageCapture;
    private CallbackContext enableCallback;
    private VideoCapture<Recorder> backVideoCapture;
    private VideoCapture<Recorder> frontVideoCapture;
    private Recording backRecording;
    private Recording frontRecording;
    private boolean activateVideo =false;
    private File backVideoFile;
    private File frontVideoFile;
    private File combinedVideoFile;
    private boolean backVideoFinalized = false;
    private boolean frontVideoFinalized = false;
    private boolean combineStarted = false;
    private boolean videoResultSent = false;
    private String videoError;
    public DualCameraPreviewFragment(CallbackContext callbackContext) {
        this.enableCallback = callbackContext;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull android.view.LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        FrameLayout root = new FrameLayout(requireContext());

        backPreviewView = createPreviewView();

        root.addView(backPreviewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        frontPreviewView = createPreviewView();

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int frontWidth = Math.round(screenWidth * 0.30f);
        int frontHeight = Math.round(frontWidth * 4f / 3f);

        FrameLayout.LayoutParams frontParams = new FrameLayout.LayoutParams(
                frontWidth,
                frontHeight
        );

        frontParams.gravity = Gravity.TOP | Gravity.LEFT;
        frontParams.topMargin = dpToPx(16);
        frontParams.rightMargin = dpToPx(16);

        root.addView(frontPreviewView, frontParams);

        return root;
    }

    private PreviewView createPreviewView(){
        PreviewView previewView = new PreviewView(requireContext());
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        return previewView;
    }

    @Override
    public void onViewCreated( @NonNull View view,@Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);
        startCamera(enableCallback);
    }

    private void startCamera(final CallbackContext callbackContext) {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(requireContext());

        future.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    cameraProvider = future.get();
                    bindDualCamera();  //bind the front and back cameras
                    if (callbackContext != null) {
                        callbackContext.success();
                    }
                } catch (Exception e) {
                    if (callbackContext != null) {
                        callbackContext.error(e.getMessage());
                    }

                }
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void bindDualCamera() {
        try {
            cameraProvider.unbindAll();

            List<List<CameraInfo>> cameraCombinations =
                    cameraProvider.getAvailableConcurrentCameraInfos();

            CameraInfo selectedFrontCameraInfo = null;
            CameraInfo selectedBackCameraInfo = null;

            for (List<CameraInfo> combination : cameraCombinations) {
                CameraInfo frontInThisCombination = null;
                CameraInfo backInThisCombination = null;

                for (CameraInfo cameraInfo : combination) {
                    Integer lensFacing = cameraInfo.getLensFacing();

                    if (lensFacing == null) {
                        continue;
                    }

                    switch (lensFacing) {
                        case CameraSelector.LENS_FACING_FRONT:
                            frontInThisCombination = cameraInfo;
                            break;
                        case CameraSelector.LENS_FACING_BACK:
                            backInThisCombination = cameraInfo;
                            break;
                    }
                }

                if (frontInThisCombination != null && backInThisCombination != null) {
                    selectedFrontCameraInfo = frontInThisCombination;
                    selectedBackCameraInfo = backInThisCombination;
                    break;
                }
            }

            if (selectedFrontCameraInfo == null || selectedBackCameraInfo == null) {
                return;
            }

            Preview backPreview = new Preview.Builder().build();
            backPreview.setSurfaceProvider(backPreviewView.getSurfaceProvider());

            backImageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();

            Preview frontPreview = new Preview.Builder().build();
            frontPreview.setSurfaceProvider(frontPreviewView.getSurfaceProvider());

            frontImageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();

            UseCaseGroup backUseCaseGroup = new UseCaseGroup.Builder()
                    .addUseCase(backPreview)
                    .addUseCase(backImageCapture)
                    .build();

            UseCaseGroup frontUseCaseGroup = new UseCaseGroup.Builder()
                    .addUseCase(frontPreview)
                    .addUseCase(frontImageCapture)
                    .build();

            List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();

            if(activateVideo==true){

                Recorder backRecorder = new Recorder.Builder()
                        .setQualitySelector(
                                QualitySelector.from(
                                        Quality.HD,
                                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                                )
                        )
                        .build();

                backVideoCapture = VideoCapture.withOutput(backRecorder);

                Recorder frontRecorder = new Recorder.Builder()
                        .setQualitySelector(
                                QualitySelector.from(
                                        Quality.HD,
                                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                                )
                        )
                        .build();

                frontVideoCapture = VideoCapture.withOutput(frontRecorder);

                UseCaseGroup backUseCaseGroupVideo = new UseCaseGroup.Builder()
                        .addUseCase(backPreview)
                        .addUseCase(backVideoCapture)
                        .build();

                UseCaseGroup frontUseCaseGroupVideo = new UseCaseGroup.Builder()
                        .addUseCase(frontPreview)
                        .addUseCase(frontVideoCapture)
                        .build();


                configs.add(new ConcurrentCamera.SingleCameraConfig(
                        selectedBackCameraInfo.getCameraSelector(),
                        backUseCaseGroupVideo,
                        this
                ));

                configs.add(new ConcurrentCamera.SingleCameraConfig(
                        selectedFrontCameraInfo.getCameraSelector(),
                        frontUseCaseGroupVideo,
                        this
                ));

            }else{

                configs.add(new ConcurrentCamera.SingleCameraConfig(
                        selectedBackCameraInfo.getCameraSelector(),
                        backUseCaseGroup,
                        this
                ));

                configs.add(new ConcurrentCamera.SingleCameraConfig(
                        selectedFrontCameraInfo.getCameraSelector(),
                        frontUseCaseGroup,
                        this
                ));

            }

            if (configs.size() != 2) {
                return;
            }

            concurrentCamera = cameraProvider.bindToLifecycle(configs);

        } catch (Exception e) {
            backImageCapture = null;
            frontImageCapture = null;
            concurrentCamera = null;
        }
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        concurrentCamera = null;
        backPreviewView = null;
        frontPreviewView = null;
    }

    public void capture(final CallbackContext callbackContext) {

        if (!isAdded() || getContext() == null) {
            callbackContext.error("Fragment is not attached");
            return;
        }

        if (backImageCapture == null || frontImageCapture == null) {
            callbackContext.error("ImageCapture is not ready");
            return;
        }

        try {
            final File filesDir = requireContext().getFilesDir();

            final File backTempFile = new File(
                    filesDir,
                    "back_" + UUID.randomUUID().toString() + ".jpg"
            );

            final File frontTempFile = new File(
                    filesDir,
                    "front_" + UUID.randomUUID().toString() + ".jpg"
            );

            final File finalFile = new File(
                    filesDir, UUID.randomUUID().toString() + ".jpg"
            );

            captureToFile(backImageCapture, backTempFile, new CaptureFileCallback() {
                @Override
                public void onSuccess(File backFile) {
                    captureToFile(frontImageCapture, frontTempFile, new CaptureFileCallback() {
                        @Override
                        public void onSuccess(File frontFile) {
                            new Thread(new Runnable() {
                                @Override
                                public void run(){
                                    try {
                                        mergeImages(backFile, frontFile, finalFile);

                                        backFile.delete();
                                        frontFile.delete();

                                        final String imageNativePath = Uri.fromFile(finalFile).toString();

                                        requireActivity().runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                callbackContext.success(imageNativePath);
                                            }
                                        });

                                    } catch (Exception e) {
                                        requireActivity().runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                callbackContext.error(e.getMessage());
                                            }
                                        });
                                    }
                                }
                            }).start();
                        }
                        @Override
                        public void onError(String error) {
                            backFile.delete();
                            callbackContext.error("Front camera capture failed: " + error);
                        }
                    });
                }
                @Override
                public void onError(String error) {
                    callbackContext.error("Back camera capture failed: " + error);
                }
            });

        } catch (Exception e) {
            callbackContext.error(e.getMessage());
        }
    }

    private void mergeImages(File backFile, File frontFile, File finalFile) throws Exception {
        Bitmap backBitmap = decodeBitmapWithCorrectOrientation(backFile);
        Bitmap frontBitmap = decodeBitmapWithCorrectOrientation(frontFile);

        frontBitmap = flipFront(frontBitmap);

        if (backBitmap == null || frontBitmap == null) {
            throw new Exception("Failed to decode captured images");
        }

        Bitmap resultBitmap = backBitmap.copy(Bitmap.Config.ARGB_8888, true);

        Canvas canvas = new Canvas(resultBitmap);

        int pipWidth = resultBitmap.getWidth() / 4;
        int pipHeight = (int) ((float) frontBitmap.getHeight() / frontBitmap.getWidth() * pipWidth);

        Bitmap resizedFrontBitmap = Bitmap.createScaledBitmap(
                frontBitmap,
                pipWidth,
                pipHeight,
                true
        );

        int margin = dpToPx(16);

        int left = margin;
        int top = margin;

        canvas.drawBitmap(resizedFrontBitmap, left, top, null);

        OutputStream outputStream = new java.io.FileOutputStream(finalFile);
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream);
        outputStream.flush();
        outputStream.close();

        backBitmap.recycle();
        frontBitmap.recycle();
        resizedFrontBitmap.recycle();
        resultBitmap.recycle();
    }

    private interface CaptureFileCallback {
        void onSuccess(File file);
        void onError(String error);
    }

    private void captureToFile(ImageCapture capture, final File file, final CaptureFileCallback callback
    ) {
        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(file).build();

        capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(requireContext()),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        callback.onSuccess(file);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        String message = exception.getMessage();

                        if (message == null) {
                            message = "Image capture failed";
                        }

                        callback.onError(message);
                    }
                }
        );
    }
    private Bitmap decodeBitmapWithCorrectOrientation(File file) throws Exception {
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());

        if (bitmap == null) {
            throw new Exception("Failed to decode image: " + file.getAbsolutePath());
        }

        ExifInterface exif = new ExifInterface(file.getAbsolutePath());

        int orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
        );

        Matrix matrix = new Matrix();

        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;

            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;

            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;

            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.preScale(-1, 1);
                break;

            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.preScale(1, -1);
                break;

            default:
                return bitmap;
        }

        Bitmap rotatedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix,
                true
        );

        bitmap.recycle();

        return rotatedBitmap;
    }

    private Bitmap flipFront(Bitmap bitmap){
        Matrix matrix = new Matrix();
        matrix.preScale(-1, 1);

        Bitmap flippedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix,
                true
        );

        return flippedBitmap;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    public void startVideoCapture(boolean recordWithAudio, int videoDurationMs) {
        activateVideo = true;
        resetVideoResultState();

        // Re-bind camera in video mode
        bindDualCamera();

        if (backVideoCapture == null || frontVideoCapture == null) {
            Log.e("DualCameraFragment", "Dual VideoCapture is not initialized");
            return;
        }

        if (backRecording != null || frontRecording != null) {
            Log.e("DualCameraFragment", "Recording already in progress");
            return;
        }

        try {
            File videoDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES);

            if (videoDir == null) {
                Log.e("DualCameraFragment", "Video directory is null");
                return;
            }

            if (!videoDir.exists()) {
                videoDir.mkdirs();
            }

            File backFile = new File(
                    videoDir,
                    "back_video_" + System.currentTimeMillis() + ".mp4"
            );

            File frontFile = new File(
                    videoDir,
                    "front_video_" + System.currentTimeMillis() + ".mp4"
            );

            backVideoFile = backFile;
            frontVideoFile = frontFile;
            combinedVideoFile = combinedFile;

            FileOutputOptions backOutputOptions =
                    new FileOutputOptions.Builder(backFile).build();

            FileOutputOptions frontOutputOptions =
                    new FileOutputOptions.Builder(frontFile).build();

            PendingRecording backPendingRecording =
                    backVideoCapture.getOutput()
                            .prepareRecording(requireContext(), backOutputOptions);

            PendingRecording frontPendingRecording =
                    frontVideoCapture.getOutput()
                            .prepareRecording(requireContext(), frontOutputOptions);

            if (recordWithAudio) {
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED) {

                    // Enable audio only on one recording
                    backPendingRecording = backPendingRecording.withAudioEnabled();

                } else {
                    Log.e("DualCameraFragment", "Audio permission not granted");
                }
            }

            backRecording = backPendingRecording.start(
                    ContextCompat.getMainExecutor(requireContext()),
                    videoRecordEvent -> {
                        if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                            Log.d("DualCameraFragment", "Back recording started");
                        }
                        if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize finalizeEvent =
                                    (VideoRecordEvent.Finalize) videoRecordEvent;

                            backRecording = null;

                            if (finalizeEvent.hasError()) {
                                String error = "Back recording failed: " + finalizeEvent.getError();
                                Log.e("DualCameraFragment", error);
                                notifyVideoError(error);
                                return;
                            }

                            backVideoFinalized = true;

                            Log.d(
                                    "DualCameraFragment",
                                    "Back video saved: " + backFile.getAbsolutePath()
                            );

                            combineVideosIfReady();
                        }
                    }
            );



            frontRecording = frontPendingRecording.start(
                    ContextCompat.getMainExecutor(requireContext()),
                    videoRecordEvent -> {
                        if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                            Log.d("DualCameraFragment", "Front recording started");
                        }

                        if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize finalizeEvent =
                                    (VideoRecordEvent.Finalize) videoRecordEvent;

                            frontRecording = null;

                            if (finalizeEvent.hasError()) {
                                String error = "Front recording failed: " + finalizeEvent.getError();
                                Log.e("DualCameraFragment", error);
                                notifyVideoError(error);
                                return;
                            }

                            frontVideoFinalized = true;

                            Log.d(
                                    "DualCameraFragment",
                                    "Front video saved: " + frontFile.getAbsolutePath()
                            );

                            combineVideosIfReady();
                        }
                    }
            );

            if (videoDurationMs > 0) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    stopVideoCapture();
                }, videoDurationMs);
            }

        } catch (Exception e) {
            Log.e("DualCameraFragment", "startVideoCapture error", e);
            // stopVideoCapture();
        }
    }

    public void stopVideoCapture() {
        try {
            if (backRecording != null) {
                backRecording.stop();
                backRecording = null;
            }

            if (frontRecording != null) {
                frontRecording.stop();
                frontRecording = null;
            }

            Log.d("DualCameraFragment", "Video recording stopped");

        } catch (Exception e) {
            Log.e("DualCameraFragment", "stopVideoCapture failed", e);
        }
    }

    public interface VideoCaptureListener {
        void onVideoSaved(String nativePath);
        void onVideoError(String error);
    }

    private VideoCaptureListener videoCaptureListener;

    public void setVideoCaptureListener(VideoCaptureListener listener) {
        this.videoCaptureListener = listener;

        if (videoError != null) {
            notifyVideoError(videoError);
        }
    }

    private void resetVideoResultState() {
        backVideoFile = null;
        frontVideoFile = null;
        combinedVideoFile = null;

        backVideoFinalized = false;
        frontVideoFinalized = false;
        combineStarted = false;
        videoResultSent = false;
        videoError = null;
    }

    private void combineVideosIfReady() {
        if (combineStarted || videoResultSent) {
            return;
        }

        if (!backVideoFinalized || !frontVideoFinalized) {
            return;
        }

        if (backVideoFile == null || frontVideoFile == null || combinedVideoFile == null) {
            notifyVideoError("Video files are missing");
            return;
        }

        combineStarted = true;

        combineFrontAndBackVideosWithMedia3(
                backVideoFile,
                frontVideoFile,
                combinedVideoFile
        );
    }

    @OptIn(markerClass = UnstableApi.class)
    private void combineFrontAndBackVideosWithMedia3(
            File backFile,
            File frontFile,
            File outputFile
    ) {
        EditedMediaItem backItem =
                new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(backFile)))
                        .build();

        EditedMediaItem frontItem =
                new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(frontFile)))
                        .setRemoveAudio(true)
                        .build();

        EditedMediaItemSequence backSequence =
                EditedMediaItemSequence.withAudioAndVideoFrom(
                        Collections.singletonList(backItem)
                );

        EditedMediaItemSequence frontSequence =
                EditedMediaItemSequence.withVideoFrom(
                        Collections.singletonList(frontItem)
                );

        VideoCompositorSettings pipSettings =
                new VideoCompositorSettings() {
                    @Override
                    public Size getOutputSize(List<Size> inputSizes) {
                        return inputSizes.get(0);
                    }

                    @Override
                    public OverlaySettings getOverlaySettings(
                            int inputId,
                            long presentationTimeUs
                    ) {
                        if (inputId == 0) {
                            // Back camera full screen.
                            return new StaticOverlaySettings.Builder().build();
                        }

                        // Front camera PiP, top-right.
                        return new StaticOverlaySettings.Builder()
                                .setScale(0.30f, 0.30f)
                                .setOverlayFrameAnchor(1f, 1f)
                                .setBackgroundFrameAnchor(0.70f, 0.70f)
                                .build();
                    }
                };

        Composition composition =
                new Composition.Builder(backSequence, frontSequence)
                        .setVideoCompositorSettings(pipSettings)
                        .build();

        Transformer transformer =
                new Transformer.Builder(requireContext())
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(
                                new Transformer.Listener() {
                                    @Override
                                    public void onCompleted(
                                            Composition composition,
                                            ExportResult result
                                    ) {
                                        String nativePath = Uri.fromFile(outputFile).toString();

                                        if (backFile.exists()) {
                                            backFile.delete();
                                        }

                                        if (frontFile.exists()) {
                                            frontFile.delete();
                                        }

                                        requireActivity().runOnUiThread(() -> {
                                            if (!videoResultSent && videoCaptureListener != null) {
                                                videoResultSent = true;
                                                videoCaptureListener.onVideoSaved(nativePath);
                                            }
                                        });
                                    }

                                    @Override
                                    public void onError(
                                            Composition composition,
                                            ExportResult result,
                                            ExportException exception
                                    ) {
                                        notifyVideoError(
                                                exception.getMessage() != null
                                                        ? exception.getMessage()
                                                        : "Failed to combine videos"
                                        );
                                    }
                                }
                        )
                        .build();

        transformer.start(composition, outputFile.getAbsolutePath());
    }

    private void notifyVideoError(String error) {
        videoError = error;

        requireActivity().runOnUiThread(() -> {
            if (!videoResultSent && videoCaptureListener != null) {
                videoResultSent = true;
                videoCaptureListener.onVideoError(error);
            }
        });
    }


}


