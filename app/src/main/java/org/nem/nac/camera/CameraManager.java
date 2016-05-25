package org.nem.nac.camera;

import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;

import org.nem.nac.application.AppHost;
import org.nem.nac.common.lang.Disposable;
import org.nem.nac.common.models.Size;
import org.nem.nac.common.utils.LogUtils;
import org.nem.nac.ui.controls.CameraSurfaceView;

import timber.log.Timber;

public final class CameraManager implements Disposable {

	private boolean _disposed = false;
	private CamProxy      _camProxy;
	private FrameCallback _frameCallback;
	private byte[] _imgBuffer = null;

	private boolean _initialized = false;
	private CameraSurfaceView _previewSurface;

	public boolean isInitialized() {
		return _initialized;
	}

	/**
	 * Returns true if success
	 */
	public boolean initialize(final CameraSurfaceView previewSurface) {
		_previewSurface = previewSurface;
		Timber.d("initialize()");
		if (_initialized) { return true; }
		_camProxy = new CamProxy();
		final boolean hasCamera = AppHost.Camera.hasCamera();
		if (!hasCamera) {
			return false;
		}
		_camProxy.setPreviewCallbackMethod(this::onFrame);
		previewSurface.getHolder().addCallback(_surfaceCallback);
		//
		try {
			final Size resolution = _camProxy.openWithInit();
			previewSurface.setAr(resolution.width / (float)resolution.height);
			final int imgBufferSize = _camProxy.getPreviewBufferSize();
			if (_imgBuffer == null || _imgBuffer.length != imgBufferSize) {
				_imgBuffer = new byte[imgBufferSize];
			}
			_initialized = true;
			return true;
		} catch (CameraException e) {
			Timber.e("Failed to start camera");
			return false;
		}
	}

	public boolean startPreview() {
		if (!_initialized) { return false; }
		_camProxy.addCallbackBuffer(_imgBuffer);
		try {
			_camProxy.startPreview();
			return true;
		} catch (CameraException e) {
			Timber.e(e, "Failed to start preview");
			return false;
		}
	}

	public boolean stopPreview() {
		if (!_initialized) { return false; }
		try {
			_camProxy.stopPreview();
			return true;
		} catch (CameraException | IllegalStateException e) {
			Timber.e(e, "Failed to stop preview");
			return false;
		}
	}

	//
	//
	//

	public void setFrameCallback(final FrameCallback callback) {
		_frameCallback = callback;
	}

	@Override
	public void close() {
		try {
			if(!_disposed && _previewSurface != null && _previewSurface.getHolder() != null) {
				_previewSurface.getHolder().removeCallback(_surfaceCallback);
			}
			_imgBuffer = null;
			if (_camProxy != null && !_disposed) {
				stopPreview();
				_camProxy.close();
			}
		} finally {
			_disposed = true;
		}
	}

	private void onFrame(final byte[] image, final Camera camera) {
		if (_camProxy == null || _disposed || !_camProxy.isOpen()) {
			Timber.d("Camera already disposed");
			return;
		}
		if (_frameCallback != null) {
			final Camera.Size previewSize = camera.getParameters().getPreviewSize();
			_frameCallback.call(image, previewSize.width, previewSize.height);
		}
		final boolean isPreviewActive = _camProxy.isPreviewActive();
		if (isPreviewActive) {
			_camProxy.addCallbackBuffer(_imgBuffer);
		}
	}

	private void ensureInitialized() {
		if (_disposed || _camProxy == null) {
			throw new IllegalStateException("Already disposed or not initialized!");
		}
	}

	public interface FrameCallback {

		void call(final byte[] image, final int width, final int height);
	}

	private final SurfaceHolder.Callback _surfaceCallback = new SurfaceHolder.Callback() {
		private static final String LOG_TAG = "SurfaceCallback";

		@Override
		public void surfaceCreated(final SurfaceHolder holder) {
			LogUtils.tagged(Log.DEBUG, LOG_TAG, "Surface created");
			if (_disposed) {
				throw new IllegalStateException("Disposed");
			}
			try {
				_camProxy.setPreviewDisplay(holder);
			} catch (CameraException e) {
				Log.e(LOG_TAG, "Failed to set preview display", e);
			}
		}

		@Override
		public void surfaceDestroyed(final SurfaceHolder holder) {
			Log.d(LOG_TAG, "Surface destroyed");
			if (_disposed) { stopPreview(); }
		}

		@Override
		public void surfaceChanged(final SurfaceHolder holder, final int format, final int width, final int height) {
			if (holder.getSurface() == null) {
				return;
			}
			LogUtils.tagged(Log.DEBUG, LOG_TAG, "Camera surface size: %d*%d", width, height);
			ensureInitialized();
			if (!_initialized) {
				LogUtils.tagged(Log.WARN, LOG_TAG, "Camera not initialized!");
				return;
			}
			final boolean previewWasActive = _camProxy.isPreviewActive();
			try {
				_camProxy.stopPreview();
			} catch (CameraException e) {
				Log.w(LOG_TAG, "Stop preview failed, maybe non-existent");
			}
			if (previewWasActive) {
				try {
					Log.d(LOG_TAG, "Preview was active, restarting");
					_camProxy.startPreview();
					_camProxy.addCallbackBuffer(_imgBuffer);
				} catch (CameraException e) {
					Log.e(LOG_TAG, "Failed to start preview", e);
				}
			}
			else {
				Log.d(LOG_TAG, "Preview was not active, skipping restart");
			}
		}
	};
}
