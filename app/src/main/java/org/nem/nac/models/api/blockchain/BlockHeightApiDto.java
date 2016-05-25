package org.nem.nac.models.api.blockchain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.nem.nac.models.BlockHeight;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class BlockHeightApiDto {
	public BlockHeight height; // The height is an integer greater than zero.

	public BlockHeightApiDto() {
	}

	public BlockHeightApiDto(final BlockHeight height) {
		this.height = height;
	}
}
