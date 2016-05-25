package org.nem.nac.models.api.blockchain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.nem.nac.common.enums.BlockType;
import org.nem.nac.common.enums.NetworkVersion;
import org.nem.nac.common.models.HashValue;
import org.nem.nac.common.models.TimeValue;
import org.nem.nac.models.BlockHeight;
import org.nem.nac.models.api.transactions.TransferTransactionApiDto;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class BlockApiDto {
	public TimeValue                   timeStamp;
	public String                      signature;
	public HashValue                   prevBlockHash;
	public BlockType                   type;
	public TransferTransactionApiDto[] transactions;
	public NetworkVersion              version;
	public String                      signer;
	public BlockHeight                 height;
}
