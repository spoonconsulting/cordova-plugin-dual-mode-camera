package com.spoon.dualcamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
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
import androidx.camera.core.UseCase;
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
import android.os.Handler;
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
import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import androidx.exifinterface.media.ExifInterface;
import android.graphics.Matrix;
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
import android.media.MediaMetadataRetriever;
import androidx.media3.common.Effect;
import androidx.media3.transformer.Effects;
import androidx.media3.effect.ScaleAndRotateTransformation;
import android.view.OrientationEventListener;

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
    private File backVideoFile;
    private File frontVideoFile;
    private File combinedVideoFile;
    private boolean backVideoFinalized = false;
    private boolean frontVideoFinalized = false;
    private boolean combineStarted = false;
    private boolean videoResultSent = false;
    private String videoError;
    private static final float frontWidthRatio = 0.30f;
    private static final float frontAspectRatio = 3f / 4f;
    private static final int margin = 16;
    private OrientationEventListener orientationEventListener;
    private int currentTargetRotation = Surface.ROTATION_0;
    
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

        FrameLayout.LayoutParams frontParams = new FrameLayout.LayoutParams(
                1,
                1
        );

        frontParams.gravity = Gravity.TOP | Gravity.START;
        frontParams.topMargin = dpToPx(margin);
        frontParams.leftMargin = dpToPx(margin);

        root.addView(frontPreviewView, frontParams);

        root.addOnLayoutChangeListener((v, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;

            if (width <= 0 || height <= 0) {
                return;
            }

            int oldWidth = oldRight - oldLeft;
            int oldHeight = oldBottom - oldTop;

            if (width == oldWidth && height == oldHeight) {
                return;
            }

            int baseWidth = Math.min(width, height);
            int frontWidth = Math.round(baseWidth * frontWidthRatio);
            int frontHeight = Math.round(frontWidth / frontAspectRatio);

            ViewGroup.LayoutParams params = frontPreviewView.getLayoutParams();
            params.width = frontWidth;
            params.height = frontHeight;

            frontPreviewView.setLayoutParams(params);
        });

        setupOrientationListener();
        return root;
    }

    private PreviewView createPreviewView(){
        PreviewView previewView = new PreviewView(requireContext());
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        return previewView;
    }

    @Override
    public void onViewCreated( @NonNull View view,@Nullable Bundle savedInstanceState) {
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
                    .setTargetRotation(currentTargetRotation)
                    .build();

            Preview frontPreview = new Preview.Builder().build();
            frontPreview.setSurfaceProvider(frontPreviewView.getSurfaceProvider());

            frontImageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(currentTargetRotation)
                    .build();

            UseCaseGroup backUseCaseGroup = createUseCaseGroup(
                    backPreview,
                    backImageCapture,
                    backVideoCapture
            );

            UseCaseGroup frontUseCaseGroup = createUseCaseGroup(
                    frontPreview,
                    frontImageCapture,
                    frontVideoCapture
            );

            List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();

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

            concurrentCamera = cameraProvider.bindToLifecycle(configs);

    } catch (Exception e) {
            backImageCapture = null;
            frontImageCapture = null;
            backVideoCapture = null;
            frontVideoCapture = null;
            concurrentCamera = null;

            if (enableCallback != null) {
                enableCallback.error(e.getMessage());
            }
        }

    }

    private ImageCapture imageCapture(){
        return new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();
    }

    private Recorder createRecorder(){
        return new Recorder.Builder()
                .setQualitySelector(
                        QualitySelector.from(Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                )
                .build();
    }

    private UseCaseGroup createUseCaseGroup(
            Preview preview,
            ImageCapture imageCapture,
            @Nullable VideoCapture<Recorder> videoCapture
    ) {
        UseCaseGroup.Builder builder = new UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageCapture);

        if (videoCapture != null) {
            builder.addUseCase(videoCapture);
        }

        return builder.build();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        if (orientationEventListener != null) {
            orientationEventListener.disable();
            orientationEventListener = null;
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

            backImageCapture.setTargetRotation(currentTargetRotation);
            frontImageCapture.setTargetRotation(currentTargetRotation);

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

    private void setupOrientationListener() {
        if (orientationEventListener != null) {
            return;
        }

        orientationEventListener = new OrientationEventListener(requireContext()) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) {
                    return;
                }

                int rotation = UseCase.snapToSurfaceRotation(orientation);

                if (rotation == currentTargetRotation) {
                    return;
                }

                currentTargetRotation = rotation;
            }
        };

        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
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

    public void startVideoCapture(int videoDurationMs, CallbackContext callbackContext) {
        resetVideoResultState();

        if (backVideoCapture == null || frontVideoCapture == null) {
            callbackContext.error("Dual VideoCapture is not initialized");
            return;
        }

        if (backRecording != null || frontRecording != null) {
            callbackContext.error( "Recording already in progress");
            return;
        }

        try {
            File videoDir = requireContext().getFilesDir();

            if (!videoDir.exists()) {
                videoDir.mkdirs();
            }

            File backFile = new File(
                    videoDir,
                    "back_video_" +  UUID.randomUUID().toString() + ".mp4"
            );

            File frontFile = new File(
                    videoDir,
                    "front_video_" +  UUID.randomUUID().toString() + ".mp4"
            );

            File combinedFile = new File(
                    videoDir,
                    UUID.randomUUID().toString() + ".mp4"
            );

            backVideoFile = backFile;
            frontVideoFile = frontFile;
            combinedVideoFile = combinedFile;

            boolean hasAudioPermission =
                    ContextCompat.checkSelfPermission(
                            requireContext(),
                            Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED;

            PendingRecording backPendingRecording =
                    backVideoCapture.getOutput()
                            .prepareRecording(
                                    requireContext(),
                                    new FileOutputOptions.Builder(backFile).build()
                            );

            if (hasAudioPermission) {
                backPendingRecording = backPendingRecording.withAudioEnabled();
            }

            PendingRecording frontPendingRecording =
                    frontVideoCapture.getOutput()
                            .prepareRecording(requireContext(), new FileOutputOptions.Builder(frontFile).build());

            backRecording = startRecording(
                    backPendingRecording,
                    "Back",
                    () -> {
                        backRecording = null;
                        backVideoFinalized = true;
                    }
            );

            frontRecording = startRecording(
                    frontPendingRecording,
                    "Front",
                    () -> {
                        frontRecording = null;
                        frontVideoFinalized = true;
                    }
            );

            if (videoDurationMs > 0) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (backRecording != null || frontRecording != null) {
                        if (autoStopListener != null) {
                            autoStopListener.onAutoStop();
                        }
                    }
                }, videoDurationMs);
            }

        } catch (Exception e) {
            callbackContext.error("startVideoCapture error" +  e);
            stopVideoCapture(callbackContext);
        }
    }

    private Recording startRecording(PendingRecording pendingRecording,   String cameraName, Runnable onFinalized){
        return pendingRecording.start(
                ContextCompat.getMainExecutor(requireContext()),
                videoRecordEvent -> {
                    if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                        VideoRecordEvent.Finalize finalizeEvent =
                                (VideoRecordEvent.Finalize) videoRecordEvent;

                        if (finalizeEvent.hasError()) {
                            String error = cameraName +  " recording failed: " + finalizeEvent.getError();
                            notifyVideoError(error);
                            return;
                        }
                        onFinalized.run();
                        combineVideosIfReady();
                    }
                }
        );
    }

    public void stopVideoCapture(CallbackContext callbackContext) {
        try {
            if (backRecording != null) {
                backRecording.stop();
                backRecording = null;
            }

            if (frontRecording != null) {
                frontRecording.stop();
                frontRecording = null;
            }

        } catch (Exception e) {
            callbackContext.error("stopVideoCapture failed" + e);
        }
    }

    public interface VideoCaptureListener {
        void onVideoSaved(String nativePath, String thumbnailNativePath);
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

    private File createVideoThumbnailFile(File videoFile) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            retriever.setDataSource(videoFile.getAbsolutePath());

            Bitmap bitmap = retriever.getFrameAtTime(
                    0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            );

            if (bitmap == null) {
                throw new Exception("Failed to create video thumbnail");
            }

            File thumbnailFile = new File(
                    videoFile.getParentFile(),
                    videoFile.getName().replace(".mp4", "_thumb.jpg")
            );

            OutputStream outputStream = new java.io.FileOutputStream(thumbnailFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
            outputStream.flush();
            outputStream.close();

            bitmap.recycle();

            return thumbnailFile;

        } finally {
            retriever.release();
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private void combineFrontAndBackVideosWithMedia3(File backFile,File frontFile,File outputFile) {
        EditedMediaItem frontItem =
                new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(frontFile)))
                        .setRemoveAudio(true)
                        .setEffects(
                                new Effects(
                                        Collections.emptyList(),
                                        Collections.<Effect>singletonList(
                                                new ScaleAndRotateTransformation.Builder()
                                                        .setScale(-1f, 1f)
                                                        .build()
                                        )
                                )
                        )
                        .build();

        EditedMediaItem backItem =
                new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(backFile)))
                        .build();

        EditedMediaItemSequence frontSequence =
                new EditedMediaItemSequence.Builder(
                        Collections.singletonList(frontItem)
                ).build();

        EditedMediaItemSequence backSequence =
                new EditedMediaItemSequence.Builder(
                        Collections.singletonList(backItem)
                ).build();

        VideoCompositorSettings pipSettings =
                new VideoCompositorSettings() {
                    @Override
                    public Size getOutputSize(List<Size> inputSizes) {
                        return inputSizes.get(1);
                    }

                    @Override
                    public OverlaySettings getOverlaySettings(
                            int inputId,
                            long presentationTimeUs
                    ) {
                        if (inputId == 0) {
                            return new StaticOverlaySettings.Builder()
                                    .setScale(frontWidthRatio, frontWidthRatio)
                                    .setOverlayFrameAnchor(-1f, 1f)
                                    .setBackgroundFrameAnchor(-0.92f, 0.92f)
                                    .build();
                        }

                        return new StaticOverlaySettings.Builder().build();
                    }
                };

        Composition composition =
                new Composition.Builder(frontSequence, backSequence)
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
                                        try {
                                            String nativePath = Uri.fromFile(outputFile).toString();

                                            File thumbnailFile = createVideoThumbnailFile(outputFile);
                                            String thumbnailNativePath = Uri.fromFile(thumbnailFile).toString();

                                            requireActivity().runOnUiThread(() -> {
                                                if (!videoResultSent && videoCaptureListener != null) {
                                                    videoResultSent = true;

                                                    videoCaptureListener.onVideoSaved(
                                                            nativePath,
                                                            thumbnailNativePath
                                                    );
                                                }
                                            });

                                            if (backFile.exists()) {
                                                backFile.delete();
                                            }

                                            if (frontFile.exists()) {
                                                frontFile.delete();
                                            }

                                        } catch (Exception e) {
                                            notifyVideoError(
                                                    e.getMessage() != null
                                                            ? e.getMessage()
                                                            : "Failed to create video thumbnail"
                                            );
                                        }
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
    
    public interface AutoStopListener {
        void onAutoStop();
    }

    private AutoStopListener autoStopListener;

    public void setAutoStopListener(AutoStopListener listener) {
        this.autoStopListener = listener;
    }
}