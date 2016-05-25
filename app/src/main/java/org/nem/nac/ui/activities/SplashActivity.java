package org.nem.nac.ui.activities;

import android.os.Bundle;
import android.os.Handler;
import android.support.design.widget.Snackbar;
import android.view.View;
import android.widget.TextView;

import org.nem.nac.BuildConfig;
import org.nem.nac.R;
import org.nem.nac.application.AppConstants;
import org.nem.nac.application.AppHost;
import org.nem.nac.datamodel.NacPersistenceRuntimeException;
import org.nem.nac.datamodel.repositories.AppPasswordRepository;
import org.nem.nac.servers.ServerFinder;
import org.nem.nac.ui.dialogs.ChangeAppPasswordDialogFragment;

import timber.log.Timber;

public final class SplashActivity extends NacBaseActivity {
	private boolean _visible = false;
	private View _coordinator;
	private ChangeAppPasswordDialogFragment _pwdDialog;
	private boolean _isStarted = false;

	@Override
	protected int getActivityTitle() {
		return R.string.title_activity_splash;
	}

	@Override
	protected int getLayoutId() {
		return R.layout.activity_splash;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		_coordinator = findViewById(R.id.layout_coordinator);

		if (BuildConfig.DEBUG) {
			final TextView splash = ((TextView)findViewById(R.id.test));
			splash.setText("Density: " + AppHost.Screen.getDensityDpi() + "; w: " + AppHost.Screen.getSize().width / AppHost.Screen
					.getDensityLogical() + "; h: " + AppHost.Screen.getSize().height / AppHost.Screen.getDensityLogical());
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		_visible = true;

		ServerFinder.instance().clearBest();

		new Handler().postDelayed(() -> {
			if (_visible && !isFinishing()) {
				try {
					if (new AppPasswordRepository().get().isPresent()) {
						startActivity(LoginActivity.getIntent(this, null, false));
						finish();
					}
					else {
						if (_pwdDialog == null) {
							_pwdDialog = ChangeAppPasswordDialogFragment.create(false)
									.setOnPasswordChangedListener(this::onPasswordSet);
							_pwdDialog.setOnCancelListener(d -> finish());
							_pwdDialog.show(getFragmentManager(), null);
						}
					}
				} catch (NacPersistenceRuntimeException e) {
					Timber.e(e, "Failed to get app password.");
					Snackbar.make(_coordinator, "Database problem\nPlease delete application data", Snackbar.LENGTH_INDEFINITE).show();
				}
			}
		}, AppConstants.SPLASH_DELAY_MS);
	}

	private void onPasswordSet() {
		finish();
		startActivity(LoginActivity.getIntent(this, null, false));
	}

	@Override
	protected void onStop() {
		_visible = false;
		super.onStop();
	}
}
