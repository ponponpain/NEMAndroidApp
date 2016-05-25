package org.nem.nac.tasks;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.widget.ImageView;

import com.squareup.okhttp.Call;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import org.nem.nac.http.HttpClient;
import org.nem.nac.models.network.Server;
import org.nem.nac.ui.activities.NacBaseActivity;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.net.InetAddress;

import timber.log.Timber;

public final class SetServerFlagAsyncTask extends AsyncTask<Void, Void, Bitmap> {

	private final SoftReference<NacBaseActivity> _activity;
	private Server    _server;
	private ImageView _imgFlag;

	public SetServerFlagAsyncTask(final NacBaseActivity activity, final Server server, final ImageView imgFlag) {
		_activity = new SoftReference<>(activity);
		_server = server;
		_imgFlag = imgFlag;
	}

	@Override
	protected Bitmap doInBackground(final Void... params) {
		try {
			final InetAddress address = InetAddress.getByName(_server.host);
			final String hostAddress = address.getHostAddress();

			final Request request = new Request.Builder().get()
					.url("http://api.hostip.info/flag.php?ip=" + hostAddress)
					.build();
			final Call call = HttpClient.instance().get().newCall(request);
			final Response response = call.execute();
			if (response.isSuccessful()) {
				final InputStream stream = response.body().byteStream();
				return BitmapFactory.decodeStream(stream);
			}
		} catch (IOException e) {
			Timber.w(e, "Failed to get flag for %s", _server);
		}
		return null;
	}

	@Override
	protected void onPostExecute(final Bitmap img) {
		final NacBaseActivity activity = _activity.get();
		if (img != null && activity != null && activity.isNotDestroyed()) {
			_imgFlag.setImageBitmap(img);
		}
	}
}
