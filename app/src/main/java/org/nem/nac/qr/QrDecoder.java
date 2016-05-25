package org.nem.nac.qr;

import android.graphics.ImageFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;

import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;

import org.nem.nac.application.NacApplication;
import org.nem.nac.common.functional.CallbackWithResult;

import java.nio.ByteBuffer;

public final class QrDecoder extends HandlerThread {

	private static final String LOG_TAG = QrDecoder.class.getSimpleName();
	private final Handler _handler;
	private Handler _mainThreadHandler = null;

	public QrDecoder() {
		super(QrDecoder.class.getName() + " decoding");
		start();
		_handler = new Handler(getLooper());
	}

	public void decodeDtoAsync(final byte[] pictureData, final int width, final int height, final CallbackWithResult<Result> onDecoded)
			throws IllegalArgumentException {
		if (pictureData == null) {
			throw new IllegalArgumentException("Data was null");
		}

		_handler.post(() -> {
			Result decodedResult = null;
			try {
				decodedResult = decode(pictureData, width, height);
			} catch (Throwable e) {
				Log.e(LOG_TAG, "Qr decoding error", e);
				decodedResult = new Result(ScanResultStatus.ERROR_DECODE_FAILURE);
			} finally {
				if (onDecoded != null) {
					if (_mainThreadHandler == null) {
						_mainThreadHandler = new Handler(Looper.getMainLooper());
					}
					if (decodedResult == null) {
						decodedResult = new Result(ScanResultStatus.ERROR_DECODE_FAILURE);
					}
					final Result theResult = decodedResult;
					_mainThreadHandler.post(() -> onDecoded.call(theResult));
				}
			}
		});
	}

	private Result decode(final byte[] yuvData, final int width, final int height) {
		Log.v(LOG_TAG, "Decoding QR from yuv started");
		BarcodeDetector barcodeDetector = new BarcodeDetector.Builder(NacApplication.getAppContext()).build();
		//
		final Frame frame = new Frame.Builder().setImageData(ByteBuffer.wrap(yuvData), width, height, ImageFormat.NV21).build();
		final SparseArray<Barcode> barcodeSparseArray = barcodeDetector.detect(frame);
		if (barcodeSparseArray.size() < 1) {
			return new Result(ScanResultStatus.ERROR_NOT_FOUND);
		}
		else {
			return new Result(ScanResultStatus.OK, barcodeSparseArray.valueAt(0).rawValue);
		}
	}

	public static class Result {

		public final ScanResultStatus status;
		public final String           text;

		public Result(final ScanResultStatus status) {
			this.status = status;
			text = null;
		}

		public Result(final ScanResultStatus status, final String text) {
			this.status = status;
			this.text = text;
		}
	}

	public enum ScanResultStatus {
		OK,
		ERROR_DECODE_FAILURE,
		ERROR_NOT_FOUND,
		ERROR_BAD_CHECKSUM,
		ERROR_BAD_FORMAT
	}
}
