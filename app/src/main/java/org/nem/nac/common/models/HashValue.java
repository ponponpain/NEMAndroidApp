package org.nem.nac.common.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.nem.nac.models.BinaryData;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class HashValue {
	public BinaryData data;

	@SuppressWarnings("unused")
	public HashValue() {
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		HashValue hashValue = (HashValue)o;

		return !(data != null ? !data.equals(hashValue.data) : hashValue.data != null);
	}

	@Override
	public int hashCode() {
		return data != null ? data.hashCode() : 0;
	}
}
