package org.nem.nac.models.transactions;

import android.os.Parcel;
import android.os.Parcelable;

import org.nem.nac.models.BinaryData;
import org.nem.nac.models.primitives.AddressValue;

public final class DismissTransactionIntentExtra implements Parcelable {

	public final AddressValue address;
	public final BinaryData   transactionHash;

	public DismissTransactionIntentExtra(final AddressValue address, final BinaryData transactionHash) {
		this.address = address;
		this.transactionHash = transactionHash;
	}

	@Override
	public int describeContents() { return 0; }

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeParcelable(this.address, 0);
		dest.writeParcelable(this.transactionHash, 0);
	}

	protected DismissTransactionIntentExtra(Parcel in) {
		this.address = in.readParcelable(AddressValue.class.getClassLoader());
		this.transactionHash = in.readParcelable(BinaryData.class.getClassLoader());
	}

	public static final Parcelable.Creator<DismissTransactionIntentExtra> CREATOR = new Parcelable.Creator<DismissTransactionIntentExtra>() {
		public DismissTransactionIntentExtra createFromParcel(Parcel source) {return new DismissTransactionIntentExtra(source);}

		public DismissTransactionIntentExtra[] newArray(int size) {return new DismissTransactionIntentExtra[size];}
	};
}
