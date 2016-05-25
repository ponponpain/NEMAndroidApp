package org.nem.nac.camera;

import org.nem.nac.common.exceptions.NacException;

public final class CameraException extends NacException {
	public CameraException(final Throwable throwable) {
		super(throwable);
	}

	public CameraException(final String detailMessage) {
		super(detailMessage);
	}
}
