package org.nem.nac.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public final class BlockHeight {
	private long _value;

	@JsonCreator
	public BlockHeight(final long value) {
		_value = value;
	}

	@JsonValue
	public long getValue() {
		return _value;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		BlockHeight that = (BlockHeight) o;

		return _value == that._value;
	}

	@Override
	public int hashCode() {
		return (int) (_value ^ (_value >>> 32));
	}

	@Override
	public String toString() {
		return String.valueOf(_value);
	}
}
