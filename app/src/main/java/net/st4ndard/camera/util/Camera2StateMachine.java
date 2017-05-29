package net.st4ndard.camera.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import net.st4ndard.camera.MyApplication;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import jp.co.cyberagent.android.gpuimage.GPUImage;
import jp.co.cyberagent.android.gpuimage.GPUImagePosterizeFilter;

public class Camera2StateMachine {
    private static final String TAG = Camera2StateMachine.class.getSimpleName();
    private CameraManager mCameraManager;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private ImageReader mImageReader;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private AutoFitImageView mImageView;

    private Handler mHandler = null; // default current thread.
    private State mState = null;
    private ImageReader.OnImageAvailableListener mTakePictureListener;
    private GPUImage gpuImage;
    private Bitmap bitmap;
    private int axis=0;

    public void open(Activity activity, AutoFitImageView imageView) {
        if (mState != null) throw new IllegalStateException("Alrady started state=" + mState);
        mImageView = imageView;
        mCameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        gpuImage = new GPUImage(MyApplication.getAppContext());
        nextState(mInitSurfaceState);
    }

    public boolean takePicture(ImageReader.OnImageAvailableListener listener) {
        if (mState != mPreviewState) return false;
        mTakePictureListener = listener;
        nextState(mAutoFocusState);
        return true;
    }

    public void close() {
        nextState(mAbortState);
    }

    // ----------------------------------------------------------------------------------------
    // The following private
    private void shutdown() {
        if (null != mCaptureSession) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != mImageReader) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    private void nextState(State nextState) {
        Log.d(TAG, "state: " + mState + "->" + nextState);
        try {
            if (mState != null) mState.finish();
            mState = nextState;
            if (mState != null) mState.enter();
        } catch (CameraAccessException e) {
            Log.e(TAG, "next(" + nextState + ")", e);
            shutdown();
        }
    }

    private abstract class State {
        private String mName;

        public State(String name) {
            mName = name;
        }

        //@formatter:off
        public String toString() {
            return mName;
        }

        public void enter() throws CameraAccessException {
        }

        public void onSurfaceTextureAvailable(int width, int height) {
        }

        public void onCameraOpened(CameraDevice cameraDevice) {
        }

        public void onSessionConfigured(CameraCaptureSession cameraCaptureSession) {
        }

        public void onCaptureResult(CaptureResult result, boolean isCompleted) throws CameraAccessException {
        }

        public void finish() throws CameraAccessException {
        }
        //@formatter:on
    }

    // ===================================================================================
    // State Definition
    private final State mInitSurfaceState = new State("InitSurface") {
        public void enter() throws CameraAccessException {
            nextState(mOpenCameraState);
        }
    };
    // -----------------------------------------------------------------------------------
    private final State mOpenCameraState = new State("OpenCamera") {
        public void enter() throws CameraAccessException {
            // configureTransform(width, height);
            String cameraId = Camera2Util.getCameraId(mCameraManager, CameraCharacteristics.LENS_FACING_BACK);
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
            Log.d("openCamera", characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE).toString());
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            mImageReader = ImageReader.newInstance(1080, 1280, ImageFormat.JPEG, 2);
            Size previewSize = Camera2Util.getBestPreviewSize(map, mImageReader);
            //mImageView.setPreviewSize(previewSize.getHeight(), previewSize.getWidth());
            try {
                mCameraManager.openCamera(cameraId, mStateCallback, mHandler);
            } catch (SecurityException e) {
                e.getStackTrace();
            }

            Log.d(TAG, "openCamera:" + cameraId);
        }

        public void onCameraOpened(CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            nextState(mCreateSessionState);
        }

        private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice cameraDevice) {
                if (mState != null) mState.onCameraOpened(cameraDevice);
            }

            @Override
            public void onDisconnected(CameraDevice cameraDevice) {
                nextState(mAbortState);
            }

            @Override
            public void onError(CameraDevice cameraDevice, int error) {
                Log.e(TAG, "CameraDevice:onError:" + error);
                nextState(mAbortState);
            }
        };


    };
    // -----------------------------------------------------------------------------------
    private final State mCreateSessionState = new State("CreateSession") {
        public void enter() throws CameraAccessException {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());
            List<Surface> outputs = Arrays.asList(mImageReader.getSurface());
            mCameraDevice.createCaptureSession(outputs, mSessionCallback, mHandler);
        }

        public void onSessionConfigured(CameraCaptureSession cameraCaptureSession) {
            mCaptureSession = cameraCaptureSession;
            nextState(mPreviewState);
        }

        private final CameraCaptureSession.StateCallback mSessionCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                if (mState != null) mState.onSessionConfigured(cameraCaptureSession);
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                nextState(mAbortState);
            }
        };
    };
    // -----------------------------------------------------------------------------------
    private final State mPreviewState = new State("Preview") {
        public void enter() throws CameraAccessException {
            mImageReader.setOnImageAvailableListener(mImageListener, mHandler);

            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback, mHandler);
        }
    };

    private final ImageReader.OnImageAvailableListener mImageListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireNextImage();
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Bitmap src = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            image.close();

            // GPUImage で写真加工
            gpuImage.setImage(src);
            GPUImagePosterizeFilter filter = new GPUImagePosterizeFilter();
            int value = 10-axis*2;
            if(value<1)value=1;
            filter.setColorLevels(value);
            gpuImage.setFilter(filter);
            bitmap = gpuImage.getBitmapWithFilterApplied();

            mImageView.setImageBitmap(bitmap);
            src.recycle();
        }
    };

    private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {
            onCaptureResult(partialResult, false);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            onCaptureResult(result, true);
        }

        private void onCaptureResult(CaptureResult result, boolean isCompleted) {
            try {
                if (mState != null) mState.onCaptureResult(result, isCompleted);
            } catch (CameraAccessException e) {
                Log.e(TAG, "handle():", e);
                nextState(mAbortState);
            }
        }
    };
    // -----------------------------------------------------------------------------------
    private final State mAutoFocusState = new State("AutoFocus") {
        public void enter() throws CameraAccessException {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback, mHandler);
        }

        public void onCaptureResult(CaptureResult result, boolean isCompleted) throws CameraAccessException {
            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
            boolean isAfReady = afState == null
                    || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED;
            if (isAfReady) {
                nextState(mAutoExposureState);
            }
        }
    };
    // -----------------------------------------------------------------------------------
    private final State mAutoExposureState = new State("AutoExposure") {
        public void enter() throws CameraAccessException {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback, mHandler);
        }

        public void onCaptureResult(CaptureResult result, boolean isCompleted) throws CameraAccessException {
            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
            boolean isAeReady = aeState == null
                    || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                    || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED;
            if (isAeReady) {
                nextState(mTakePictureState);
            }
        }
    };
    // -----------------------------------------------------------------------------------
    private final State mTakePictureState = new State("TakePicture") {
        public void enter() throws CameraAccessException {
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90); // portraito
            mImageReader.setOnImageAvailableListener(mTakePictureListener, mHandler);

            mCaptureSession.stopRepeating();
            mCaptureSession.capture(captureBuilder.build(), mCaptureCallback, mHandler);
        }

        public void onCaptureResult(CaptureResult result, boolean isCompleted) throws CameraAccessException {
            if (isCompleted) {
                nextState(mPreviewState);
            }
        }

        public void finish() throws CameraAccessException {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mHandler);
            mTakePictureListener = null;
        }
    };
    // -----------------------------------------------------------------------------------
    private final State mAbortState = new State("Abort") {
        public void enter() throws CameraAccessException {
            shutdown();
            nextState(null);
        }
    };
    public void setAxis(int axis){
        this.axis = axis;
    }

}
