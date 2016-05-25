package org.nem.nac.camera;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.nem.nac.application.AppHost;
import org.nem.nac.common.TimeSpan;
import org.nem.nac.common.lang.Disposable;
import org.nem.nac.common.models.Size;
import org.nem.nac.common.utils.ErrorUtils;
import org.nem.nac.common.utils.LogUtils;
import org.nem.nac.log.LogTags;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

/**
 * Camera Proxy
 */
@SuppressWarnings("deprecation")
public final class CamProxy implements Disposable {

	private static final float                   MAX_EXPOSURE_COMPENSATION = 1.5f;
	private static final float                   MIN_EXPOSURE_COMPENSATION = 0.0f;
	private static final int                     AREA_PER_1000             = 400;
	private static final boolean                 IS_LOGGED                 = true;
	private static final String                  LOG_TAG                   = "CamProxy";
	private static final int                     OPERATION_TIMEOUT_MS      = 10000;
	private              boolean                 _disposed                 = false;
	private final        AtomicReference<Camera> _camera                   = new AtomicReference<>(null);
	private final        AtomicBoolean           _open                     = new AtomicBoolean(false);
	private final HandlerThread _thread;
	private final CameraHandler _handler;

	private volatile Throwable                               _error                          = null;
	private final    ConditionVariable                       _locker                         = new ConditionVariable(true);
	private final    AtomicReference<Camera.Parameters>      _parameters                     = new AtomicReference<>(null);
	private final    AtomicBoolean                           _previewActive                  = new AtomicBoolean(false);
	private final    AtomicReference<Integer>                _previewBufferSize              = new AtomicReference<>(null);
	private final    AtomicReference<Camera.PreviewCallback> _bufferedCallback               = new AtomicReference<>(null);

	private static void setFocusArea(Camera.Parameters parameters) {
		if (parameters.getMaxNumFocusAreas() > 0) {
			List<Camera.Area> middleArea = buildMiddleArea(AREA_PER_1000);
			parameters.setFocusAreas(middleArea);
		}
		else {
			Timber.d("Device does not support focus areas");
		}
	}

	private static void setFocus(Camera.Parameters parameters,
			boolean autoFocus,
			boolean disableContinuous,
			boolean safeMode) {
		List<String> supportedFocusModes = parameters.getSupportedFocusModes();
		String focusMode = null;
		if (autoFocus) {
			if (safeMode || disableContinuous) {
				focusMode = findSettableValue("focus mode",
						supportedFocusModes,
						Camera.Parameters.FOCUS_MODE_AUTO);
			}
			else {
				focusMode = findSettableValue("focus mode",
						supportedFocusModes,
						Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO,
						Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
						Camera.Parameters.FOCUS_MODE_AUTO);
			}
		}
// Maybe selected auto-focus but not available, so fall through here:
		if (!safeMode && focusMode == null) {
			focusMode = findSettableValue("focus mode",
					supportedFocusModes,
					Camera.Parameters.FOCUS_MODE_MACRO,
					Camera.Parameters.FOCUS_MODE_EDOF);
		}
		if (focusMode != null) {
			if (focusMode.equals(parameters.getFocusMode())) {
				Timber.d("Focus mode already set to " + focusMode);
			}
			else {
				parameters.setFocusMode(focusMode);
			}
		}
	}

	private static String findSettableValue(String name,
			Collection<String> supportedValues,
			String... desiredValues) {
		Timber.d("Requesting " + name + " value from among: " + Arrays.toString(desiredValues));
		Timber.d("Supported " + name + " values: " + supportedValues);
		if (supportedValues != null) {
			for (String desiredValue : desiredValues) {
				if (supportedValues.contains(desiredValue)) {
					Timber.d("Can set " + name + " to: " + desiredValue);
					return desiredValue;
				}
			}
		}
		Timber.d("No supported values match");
		return null;
	}

	private static List<Camera.Area> buildMiddleArea(int areaPer1000) {
		return Collections.singletonList(
				new Camera.Area(new Rect(-areaPer1000, -areaPer1000, areaPer1000, areaPer1000), 1));
	}

	private static void setBestExposure(Camera.Parameters parameters, boolean lightOn) {
		int minExposure = parameters.getMinExposureCompensation();
		int maxExposure = parameters.getMaxExposureCompensation();
		float step = parameters.getExposureCompensationStep();
		if ((minExposure != 0 || maxExposure != 0) && step > 0.0f) {
// Set low when light is on
			float targetCompensation = lightOn ? MIN_EXPOSURE_COMPENSATION : MAX_EXPOSURE_COMPENSATION;
			int compensationSteps = Math.round(targetCompensation / step);
			float actualCompensation = step * compensationSteps;
// Clamp value:
			compensationSteps = Math.max(Math.min(compensationSteps, maxExposure), minExposure);
			if (parameters.getExposureCompensation() == compensationSteps) {
				Timber.d("Exposure compensation already set to " + compensationSteps + " / " + actualCompensation);
			}
			else {
				Timber.d("Setting exposure compensation to " + compensationSteps + " / " + actualCompensation);
				parameters.setExposureCompensation(compensationSteps);
			}
		}
		else {
			Timber.d("Camera does not support exposure compensation");
		}
	}

	public CamProxy() {
		_thread = new HandlerThread("Camera thread");
		_thread.start();
		_handler = new CameraHandler(_thread.getLooper());
	}

	private synchronized void open()
			throws CameraException {
		disposedCheck();
		_error = null;
		_locker.close();
		_handler.obtainMessage(Msg.OPEN).sendToTarget();
		if (!_locker.block(OPERATION_TIMEOUT_MS)) {
			throw new CameraException(String.format("Operation \"%s\" timeout after %dms!", "Open", OPERATION_TIMEOUT_MS));
		}
		if (_error == null && _camera.get() != null) {
			LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "Camera opened");
		}
		else {
			throw new CameraException(_error);
		}
	}

	/**
	 * Opens and initializes camera. Returns set preview frame resolution.
	 *
	 * @throws CameraException
	 */
	public synchronized Size openWithInit()
			throws CameraException {
		open();
		final Camera.Parameters parameters = getParameters();
		setBarcodeSceneMode(parameters);
		setBestExposure(parameters, false);
		setFocus(parameters, true, false, false);
		setFocusArea(parameters);
		setParameters(parameters);
		return setBestPreviewSize();
	}

	private void setBarcodeSceneMode(Camera.Parameters parameters) {
		if (Camera.Parameters.SCENE_MODE_BARCODE.equals(parameters.getSceneMode())) {
			Timber.d("Barcode scene mode already set");
			return;
		}
		final List<String> supportedSceneModes = parameters.getSupportedSceneModes();
		if (supportedSceneModes != null && supportedSceneModes.contains(Camera.Parameters.SCENE_MODE_BARCODE)) {
			parameters.setSceneMode(Camera.Parameters.SCENE_MODE_BARCODE);
		}
	}

	@Override
	public void close() {
		if (!_disposed) {
			try {
				release();
			} catch (CameraException e) {
				String msg = "Failed to release camera";
				Timber.e(e, msg);
				ErrorUtils.sendSilentReport(msg, e);
			}
			_bufferedCallback.set(null);
			try {

				if (Build.VERSION.SDK_INT >= 18) {
					_thread.quitSafely();
				}
				else {
					_thread.quit();
				}
			} catch (Throwable e) {
				String msg = "Camera proxy dispose failed";
				Timber.e(e, msg);
				ErrorUtils.sendSilentReport(msg, e);
			}
			_disposed = true;
			LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, " Instance disposed");
		}
	}

	public boolean isOpen() {
		disposedCheck();
		return _open.get();
	}

	public boolean isPreviewActive() {
		disposedCheck();
		return _previewActive.get();
	}

	public synchronized void setPreviewDisplay(final SurfaceHolder holder)
			throws CameraException {
		disposedCheck();
		_error = null;
		_locker.close();
		_handler.obtainMessage(Msg.SET_PREVIEW_DISPLAY, holder).sendToTarget();
		if (!_locker.block(OPERATION_TIMEOUT_MS)) {
			throw new CameraException(String.format("Operation \"%s\" timeout after %dms!", "setPreviewDisplay", OPERATION_TIMEOUT_MS));
		}
		if (_error != null) {
			throw new CameraException(_error);
		}
		LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "Preview display set");
	}

	public void addCallbackBuffer(final byte[] buffer) {
		disposedCheck();
		_error = null;
		LogUtils.tagged(Log.DEBUG, LOG_TAG, "addCallbackBuffer(buffer)");
		_handler.obtainMessage(Msg.ADD_CALLBACK_BUFFER, buffer).sendToTarget();
	}

	/**
	 * This sets callback that will be attached to camera every time a buffer is added.
	 */
	public synchronized void setPreviewCallbackMethod(@Nullable final Camera.PreviewCallback callback) {
		disposedCheck();
		_bufferedCallback.set(callback);
		LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "%s buffered callback", callback != null ? "Added" : "Removed");
	}

	public Camera.Parameters getParameters()
			throws CameraException {
		disposedCheck();
		_error = null;
		_parameters.set(null);
		_locker.close();
		_handler.obtainMessage(Msg.GET_PARAMETERS).sendToTarget();
		if (!_locker.block(OPERATION_TIMEOUT_MS)) {
			throw new CameraException(String.format("Operation \"%s\" timeout after %dms!", "getParameters", OPERATION_TIMEOUT_MS));
		}
		if (_error != null) {
			throw new CameraException(_error);
		}
		return _parameters.get();
	}

	public void setParameters(@NonNull final Camera.Parameters parameters)
			throws CameraException {
		disposedCheck();
		_error = null;
		_locker.close();
		_handler.obtainMessage(Msg.SET_PARAMETERS, parameters).sendToTarget();
		if (!_locker.block(OPERATION_TIMEOUT_MS)) {
			throw new CameraException(String.format("Operation \"%s\" timeout after %dms!", "setParameters", OPERATION_TIMEOUT_MS));
		}
		if (_error == null) {
			LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "Parameters set");
		}
		else {
			throw new CameraException(_error);
		}
	}

	public void startPreview()
			throws CameraException {
		disposedCheck();
		_error = null;
		_locker.close();
		_handler.obtainMessage(Msg.START_PREVIEW).sendToTarget();
		if (!_locker.block(OPERATION_TIMEOUT_MS)) {
			throw new CameraException(String.format("Operation \"%s\" timeout after %dms!", "startPreview", OPERATION_TIMEOUT_MS));
		}
		if (_error != null) {
			throw new CameraException(_error);
		}
		LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "Preview started");
	}

	public void stopPreview()
			throws CameraException {
		disposedCheck();
		_error = null;
		_locker.close();
		_handler.obtainMessage(Msg.STOP_PREVIEW).sendToTarget();
		if (!_locker.block(OPERATION_TIMEOUT_MS)) {
			throw new CameraException(String.format("Operation \"%s\" timeout after %dms!", "stopPreview", OPERATION_TIMEOUT_MS));
		}
		if (_error != null) {
			throw new CameraException(_error);
		}
		LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "Preview stopped");
	}

	public void release()
			throws CameraException {
		LogUtils.conditional(Log.DEBUG, LogTags.CAMERA_INIT.isLogged, LogTags.CAMERA_INIT.name, "release()");
		disposedCheck();
		_locker.close();
		_handler.obtainMessage(Msg.RELEASE).sendToTarget();
		if (!_locker.block(OPERATION_TIMEOUT_MS)) {
			throw new CameraException(String.format("Operation \"%s\" timeout after %dms!", "release", OPERATION_TIMEOUT_MS));
		}
		LogUtils.conditional(Log.DEBUG, LogTags.CAMERA_INIT.isLogged, LogTags.CAMERA_INIT.name, "Camera released");
	}

	@SuppressWarnings("deprecation")
	public Size setBestPreviewSize()
			throws CameraException {
		LogUtils.conditional(Log.DEBUG, LogTags.CAMERA_INIT.isLogged, LogTags.CAMERA_INIT.name, "setBestPreviewSize()");
		final Camera.Parameters parameters = getParameters();

		List<Camera.Size> allSizes = parameters.getSupportedPreviewSizes();
		Camera.Size acceptedSize = null;

		if (!allSizes.isEmpty()) {
			final List<Camera.Size> acceptableSizes = // More square resolutions not larger than 3.3MPixels
					Stream.of(allSizes)
							.filter(x -> (x.height / (float)x.width) >= (0.56f - 0.01f))
							.collect(Collectors.toList());

			final List<Camera.Size> sortedByAreaDesc = // Sort accepted or all so bigger comes first.
					Stream.of(!acceptableSizes.isEmpty() ? acceptableSizes : allSizes)
							.sorted((s1, s2) -> s2.width * s2.height - s1.width * s1.height)
							.collect(Collectors.toList());
			acceptedSize = sortedByAreaDesc.get(0);
		}

		if (acceptedSize != null) {
			parameters.setPreviewSize(acceptedSize.width, acceptedSize.height);
			setParameters(parameters);
		}
		final Camera.Size previewSize = parameters.getPreviewSize();

		LogUtils.conditional(Log.DEBUG, LogTags.CAMERA_INIT.isLogged, LogTags.CAMERA_INIT.name, "Preview size set: " + previewSize.width + "x" + previewSize.height);
		return new Size(previewSize.width, previewSize.height);
	}

	public int getPreviewBufferSize()
			throws CameraException {
		Integer size = _previewBufferSize.get();
		if (size == null) {
			size = previewBufferSizeFromParams(getParameters());
			_previewBufferSize.set(size);
		}
		return size;
	}

	private int previewBufferSizeFromParams(final Camera.Parameters parameters) {
		final Camera.Size size = parameters.getPreviewSize();
		final int format = parameters.getPreviewFormat();
		int imgBufSize = size.width * size.height * ImageFormat.getBitsPerPixel(format);
		final boolean divideOk = imgBufSize % 8 == 0;
		imgBufSize = ((int)(imgBufSize / 8.0f));
		if (!divideOk) {
			imgBufSize++;
		}
		return imgBufSize;
	}

	private void disposedCheck() {
		if (_disposed) { throw new IllegalStateException("Instance is already disposed!"); }
	}

	/**
	 * Camera handler
	 */
	private class CameraHandler extends Handler {

		private static final boolean IS_LOGGED = false;
		private final        String  LOG_TAG   = "CameraHandler";
		private static final int      AUTOFOCUS_MIN_DELAY_MS = 200;
		private              TimeSpan _autofocusTime         = TimeSpan.ZERO;

		public CameraHandler(final Looper looper) {
			super(looper);
		}

		@Override
		public void handleMessage(Message msg) {
			try {
				final Camera camera = _camera.get();
				switch (msg.what) {
					case Msg.OPEN: {
						if (!_open.get()) {
							_camera.set(Camera.open());
							_open.set(true);
							LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "Camera opened");
						}
						else {
							Timber.w("Camera already opened");
						}
						break;
					}
					case Msg.RELEASE: {
						releaseCamera();
						LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "Camera released");
						break;
					}
					case Msg.SET_PREVIEW_DISPLAY: {
						camera.setPreviewDisplay(((SurfaceHolder)msg.obj));
						LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "Preview display set");
						break;
					}
					case Msg.START_PREVIEW: {
						switch (AppHost.Screen.getRotation()) {
							case Surface.ROTATION_0:
								camera.setDisplayOrientation(90);
								break;
							case Surface.ROTATION_90:
								break;
							case Surface.ROTATION_180:
								break;
							case Surface.ROTATION_270:
								camera.setDisplayOrientation(180);
								break;
						}
						camera.startPreview();
						_previewActive.set(true);
						LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "Preview started");
						break;
					}
					case Msg.STOP_PREVIEW: {
						camera.stopPreview();
						_previewActive.set(false);
						LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "Preview stopped");
						break;
					}
					case Msg.ADD_CALLBACK_BUFFER: {
						final Camera.PreviewCallback callback = _bufferedCallback.get();
						if (callback == null) { LogUtils.conditional(Log.WARN, IS_LOGGED, LOG_TAG, "Added buffer but callback not set"); }
						camera.setPreviewCallbackWithBuffer(callback);
						camera.addCallbackBuffer((byte[])msg.obj);
						LogUtils.conditional(Log.VERBOSE, IS_LOGGED, LOG_TAG, "Callback buffer added");
						return;
					}
					case Msg.GET_PARAMETERS: {
						_parameters.set(camera.getParameters());
						break;
					}
					case Msg.SET_PARAMETERS: {
						final Camera.Parameters parameters = (Camera.Parameters)msg.obj;
						camera.setParameters(parameters);
						_previewBufferSize.set(previewBufferSizeFromParams(parameters));
						LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "Parameters set");
						break;
					}
					case Msg.AUTO_FOCUS_LOOP: {
						camera.autoFocus((success, camera1) -> {
							final TimeSpan now = TimeSpan.fromNanoSeconds(System.nanoTime());
							final int delayMs = Math.max(0, AUTOFOCUS_MIN_DELAY_MS - ((int)now.subtract(_autofocusTime).toMilliSeconds()));
							final Message message = obtainMessage(Msg.AUTO_FOCUS_LOOP);
							Timber.v("Autofocus end, next in %dms", delayMs);
							sendMessageDelayed(message, delayMs);
						});
						_autofocusTime = TimeSpan.fromNanoSeconds(System.nanoTime());
						LogUtils.conditional(Log.DEBUG, IS_LOGGED, LOG_TAG, "Autofocus loop started");
						break;
					}
				}
			} catch (Exception e) {
				_error = e;
				Timber.w(e, "Camera handler exception");
				if (msg.what != Msg.RELEASE) {
					try {
						releaseCamera();
						Timber.d("Camera released after exception");
					} catch (Throwable throwable) {
						Timber.e(throwable, "Failed to release camera!");
					}
				}
			}
			_locker.open();
		}

		private void releaseCamera() {
			removeMessages(Msg.AUTO_FOCUS_LOOP);
			_previewActive.set(false);
			_previewBufferSize.set(null);
			final Camera camera = _camera.get();
			if (_open.get()) {
				camera.release();
			}
			_open.set(false);
		}
	}

	private static class Msg {

		public static final int OPEN                = 1;
		public static final int RELEASE             = 2;
		public static final int SET_PREVIEW_DISPLAY = 3;
		public static final int START_PREVIEW       = 4;
		public static final int STOP_PREVIEW        = 5;
		public static final int ADD_CALLBACK_BUFFER = 7;
		public static final int GET_PARAMETERS      = 8;
		public static final int SET_PARAMETERS      = 9;
		public static final int AUTO_FOCUS_LOOP     = 10;
	}
}
