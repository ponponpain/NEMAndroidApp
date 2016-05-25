package org.nem.nac.ui;

import android.os.Handler;
import android.os.Looper;

import org.nem.nac.common.TimeSpan;

public final class IntervalCaller {

	private Handler  _mainHandler;
	private int      _intervalMs;
	private Runnable _callback;
	private boolean _running = false;

	public IntervalCaller(final TimeSpan interval, final Runnable callback) {
		_mainHandler = new Handler(Looper.getMainLooper());
		_intervalMs = ((int)interval.toMilliSeconds());
		_callback = callback;
	}

	public synchronized boolean isRunning() {
		return _running;
	}

	public synchronized void start(final boolean callNow) {
		if (_running && callNow) {
			stop();
		}
		if (!_running) {
			if (callNow) {
				_mainHandler.post(_looperRunnable);
			}
			else {
				_mainHandler.postDelayed(_looperRunnable, _intervalMs);
			}
			_running = true;
		}
	}

	public synchronized void stop() {
		if (_running) {
			_mainHandler.removeCallbacks(_looperRunnable);
			_running = false;
		}
	}

	private final Runnable _looperRunnable = new Runnable() {
		@Override
		public void run() {
			if (_callback != null) {
				_callback.run();
			}
			_mainHandler.postDelayed(_looperRunnable, _intervalMs);
		}
	};
}
