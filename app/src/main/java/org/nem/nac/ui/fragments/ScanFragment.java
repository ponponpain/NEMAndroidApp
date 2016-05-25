package org.nem.nac.ui.fragments;

import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.google.android.gms.vision.barcode.BarcodeDetector;

import org.nem.nac.R;
import org.nem.nac.application.AppHost;
import org.nem.nac.camera.CameraManager;
import org.nem.nac.common.utils.AssertUtils;
import org.nem.nac.common.utils.LogUtils;
import org.nem.nac.common.utils.StringUtils;
import org.nem.nac.crypto.NacCryptoException;
import org.nem.nac.datamodel.repositories.AccountRepository;
import org.nem.nac.helpers.ContactsHelper;
import org.nem.nac.log.LogTags;
import org.nem.nac.models.BinaryData;
import org.nem.nac.models.EncryptedNacPrivateKey;
import org.nem.nac.models.NacPrivateKey;
import org.nem.nac.models.NacPublicKey;
import org.nem.nac.models.account.Account;
import org.nem.nac.models.account.PublicAccountData;
import org.nem.nac.models.qr.BaseQrData;
import org.nem.nac.models.qr.QrAccount;
import org.nem.nac.models.qr.QrDto;
import org.nem.nac.models.qr.QrInvoice;
import org.nem.nac.models.qr.QrUserInfo;
import org.nem.nac.providers.AddressInfoProvider;
import org.nem.nac.providers.EKeyProvider;
import org.nem.nac.qr.QrDecoder;
import org.nem.nac.qr.QrResultDecoder;
import org.nem.nac.ui.activities.AccountListActivity;
import org.nem.nac.ui.activities.NewTransactionActivity;
import org.nem.nac.ui.controls.CameraSurfaceView;
import org.nem.nac.ui.dialogs.CheckPasswordDialogFragment;
import org.nem.nac.ui.dialogs.ConfirmDialogFragment;
import org.nem.nac.ui.dialogs.UserInfoImportDialogFragment;
import org.nem.nac.ui.utils.Toaster;

import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

public final class ScanFragment extends BaseTabFragment {

	private static final String ARG_BOOL_ACCOUNTS_ONLY = "accounts-only";

	private static final int RESTART_DELAY_SMALL = 300;
	private static final int RESTART_DELAY_BIG   = 1500;

	public static ScanFragment create(final boolean accountsOnly) {
		ScanFragment fragment = new ScanFragment();
		Bundle args = new Bundle();
		args.putBoolean(ARG_BOOL_ACCOUNTS_ONLY, accountsOnly);
		fragment.setArguments(args);
		return fragment;
	}

	private       CameraManager _cameraManager = new CameraManager();
	private final Handler       _mainHandler   = new Handler(Looper.getMainLooper());
	private CameraSurfaceView _preview;
	private QrDecoder _qrDecoder = null;
	private QrResultDecoder _qrResultDecoder;
	private final AtomicBoolean _decodeFrames = new AtomicBoolean(false);
	private boolean _accountsOnly = false;

	/**
	 * Releaes camera for other apps
	 */
	public void releaseCamera() {
		if (_cameraManager != null) { _cameraManager.close(); }
		_cameraManager = new CameraManager();
	}

	public void freeResources() {
		if (_cameraManager != null) { _cameraManager.close(); }
	}

	//region Lifecycle
	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		//
		LogUtils.conditional(Log.DEBUG, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "onCreate()+");
		final Bundle args = getArguments();
		if (args != null) {
			_accountsOnly = args.getBoolean(ARG_BOOL_ACCOUNTS_ONLY, false);
		}
		LogUtils.conditional(Log.DEBUG, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "onCreate()-");
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		LogUtils.conditional(Log.DEBUG, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "onCreateView()+");
		final View layout = inflater.inflate(R.layout.fragment_scan, container, false);
		_preview = (CameraSurfaceView)layout.findViewById(R.id.camera_preview);
		LogUtils.conditional(Log.DEBUG, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "onCreateView()-");
		return layout;
	}

	@Override
	public void onDetach() {
		super.onDetach();
		LogUtils.conditional(Log.DEBUG, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "onDetach()+");
		freeResources();
		try {
			_mainHandler.removeCallbacksAndMessages(null);
		} catch (Throwable throwable) {
			Timber.wtf(throwable, "Failed to removeCallbacksAndMessages from main handler");
		}
		LogUtils.conditional(Log.DEBUG, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "onDetach()-");
	}

	//endregion

	@Override
	protected void onFullyVisible() {
		super.onFullyVisible();
		LogUtils.conditional(Log.DEBUG, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "onFullyVisible()+");
		if(!new BarcodeDetector.Builder(getActivity()).build().isOperational()) {
			Toaster.instance().show(R.string.errormessage_barcode_scanner_dependencies_not_available);
			// Check for low storage. If there is low storage, the native library will not be
			// downloaded, so detection will not become operational.
			IntentFilter lowstorageFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
			boolean hasLowStorage = getActivity().registerReceiver(null, lowstorageFilter) != null;
			if (hasLowStorage) {
				Timber.e("Low storage - won't load barcode libraries!");
			}
		}
		// If something not initialized - initialize it once
		if (_qrDecoder == null) { _qrDecoder = new QrDecoder(); }
		//
		if (_qrResultDecoder == null) {
			_qrResultDecoder = new QrResultDecoder(_accountsOnly);
			_qrResultDecoder.setCallbacks(this::onQrNotFound, this::onQrError, this::onQrScanSuccess);
		}
		//
		if (_cameraManager == null) { _cameraManager = new CameraManager(); }
		//
		if (!_cameraManager.isInitialized()) {
			if (!_cameraManager.initialize(_preview)) {
				Timber.e("Failed to init camera manager!");
				Toaster.instance().show(R.string.errormessage_failed_to_init_camera);
				return;
			}
			_cameraManager.setFrameCallback(this::onFrame);
		}
		//
		if (_cameraManager.startPreview()) {
			_decodeFrames.set(true);
		}
		else {
			LogUtils.conditional(Log.ERROR, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "Failed to start preview");
			Toaster.instance().showGeneralError();
		}
		LogUtils.conditional(Log.DEBUG, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "onFullyVisible()-");
	}

	@Override
	protected void onHiding() {
		super.onHiding();
		LogUtils.conditional(Log.DEBUG, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "onHiding()+");
		if (!_cameraManager.stopPreview()) {
			LogUtils.conditional(Log.ERROR, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "Failed to stop preview");
		}
		LogUtils.conditional(Log.DEBUG, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "onHiding()-");
	}

	private void onFrame(final byte[] image, final int width, final int height) {
		final boolean decodeFrames = _decodeFrames.get();
		if (!isFullyVisible || !decodeFrames) {
			return;
		}

		_decodeFrames.set(false);
		LogUtils.conditional(Log.DEBUG, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "Decoding...");
		_qrDecoder.decodeDtoAsync(image, width, height, this::onDecodingComplete);
	}

	//region QR Decode

	private void onDecodingComplete(final QrDecoder.Result result) {
		LogUtils.conditional(Log.DEBUG, LogTags.SCAN_FRAGMENT.isLogged, LogTags.SCAN_FRAGMENT.name, "onDecodingComplete()+");
		if (!isFullyVisible) {
			Timber.d("Fragment not visible, skipping");
			_decodeFrames.set(false);
			return;
		}
		_qrResultDecoder.decodeResult(result);
	}

	private void onQrScanSuccess(final BaseQrData qrData, final QrDto.Type type) {
		switch (type) {
			case ACCOUNT: {
				final QrAccount qrAccount = (QrAccount)qrData;
				CheckPasswordDialogFragment.create()
						.setOnPasswordConfirmListener(dialog -> {
							// Decrypting private key using entered pwd and salt from QR
							final NacPrivateKey privKey;
							try {
								final BinaryData eKey = dialog.deriveKey(qrAccount.salt);
								privKey = qrAccount.privateKey.decryptKey(eKey);
							} catch (NacCryptoException e) {
								Timber.e(e, "Decrypt failed");
								Toaster.instance().show(R.string.errormessage_failed_to_import_account);
								postCanDecodeFrames(RESTART_DELAY_BIG);
								return;
							}
							// Check if account already exists
							final NacPublicKey publicKey = NacPublicKey.fromPrivateKey(privKey);
							if (new AccountRepository().find(publicKey).isPresent()) {
								ConfirmDialogFragment.create(true, null, R.string.errormessage_account_already_exists, null)
										.setOnDismissListener(d -> postCanDecodeFrames(RESTART_DELAY_BIG))
										.show(getActivity().getFragmentManager(), null);
								return;
							}
							try {
								importQrAccount(qrAccount.name, privKey, publicKey);
							} catch (NacCryptoException e) {
								Toast.makeText(getActivity(), R.string.errormessage_failed_to_import_account, Toast.LENGTH_SHORT).show();
								postCanDecodeFrames(RESTART_DELAY_BIG);
							}
						})
						.setOnCancelListener(dialog -> postCanDecodeFrames(RESTART_DELAY_BIG))
						.show(getActivity().getFragmentManager(), null);
				break;
			}
			case INVOICE: {
				final QrInvoice qrInvoice = (QrInvoice)qrData;
				final Intent intent = new Intent(getActivity(), NewTransactionActivity.class)
						.putExtra(NewTransactionActivity.EXTRA_STR_ADDRESS, qrInvoice.address.getRaw())
						.putExtra(NewTransactionActivity.EXTRA_STR_MESSAGE, qrInvoice.message)
						.putExtra(NewTransactionActivity.EXTRA_DOUBLE_AMOUNT, qrInvoice.amount.getAsFractional())
						.putExtra(NewTransactionActivity.EXTRA_BOOL_ENCRYPTED, true);
				startActivity(intent);
				getActivity().finish();
				break;
			}
			case USER_INFO: {
				final QrUserInfo qrUserInfo = (QrUserInfo)qrData;
				UserInfoImportDialogFragment.create(qrUserInfo.name, qrUserInfo.address)
						.setOnConfirmListener((add, sendTransaction, name) -> {
							boolean scanFurther = false;
							if (add) {
								try {
									ContactsHelper.addContact(getActivity(), name, qrUserInfo.address);
									scanFurther = true;
								} catch (Exception e) {
									Timber.e(e, "Failed to save contact %s", qrUserInfo);
									Toaster.instance().show(R.string.errormessage_failed_to_save_contact);
									scanFurther = true;
								}
							}
							if (sendTransaction) {
								final Intent intent = new Intent(getActivity(), NewTransactionActivity.class)
										.putExtra(NewTransactionActivity.EXTRA_STR_ADDRESS, qrUserInfo.address.getRaw());
								startActivity(intent);
								scanFurther = false;
							}
							if (scanFurther) {
								postCanDecodeFrames(RESTART_DELAY_BIG);
							}
						})
						.setOnCancelListener(dialog -> postCanDecodeFrames(RESTART_DELAY_BIG))
						.show(getActivity().getFragmentManager(), null);
				break;
			}
		}
	}

	private void onQrError(final Integer msgRes) {
		AppHost.Vibro.vibrateTwoShort();
		Toaster.instance().show(msgRes);
		if (isFullyVisible) { postCanDecodeFrames(RESTART_DELAY_BIG); }
	}

	private void onQrNotFound() {
		Timber.d("QR not found, allowing further scans in %dms", RESTART_DELAY_SMALL);
		// no qr, restart fast to check again
		if (isFullyVisible) { postCanDecodeFrames(RESTART_DELAY_SMALL); }
	}

	//endregion

	private void importQrAccount(
			@NonNull final String name, @NonNull final NacPrivateKey privateKey, @NonNull final NacPublicKey publicKey)
			throws NacCryptoException {
		AssertUtils.notNull(name, privateKey, publicKey);

		final EncryptedNacPrivateKey encryptedKey =
				privateKey.encryptKey(EKeyProvider.instance().getKey().get());
		final Account account =
				new Account(name, encryptedKey, new PublicAccountData(publicKey));
		new AccountRepository().save(account);
		AddressInfoProvider.instance().invalidateLocal();
		Toast.makeText(getActivity(), StringUtils.format(R.string.message_account_imported, name), Toast.LENGTH_SHORT)
				.show();
		AccountListActivity.start(getActivity());
		getActivity().finish();
	}

	private void postCanDecodeFrames(final int delayMs) {
		_mainHandler.postDelayed(() -> _decodeFrames.set(true), delayMs);
	}
}
