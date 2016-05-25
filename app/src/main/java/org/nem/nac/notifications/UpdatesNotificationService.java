package org.nem.nac.notifications;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;

import com.annimon.stream.Collectors;
import com.annimon.stream.Optional;
import com.annimon.stream.Stream;

import org.nem.nac.R;
import org.nem.nac.application.AppHost;
import org.nem.nac.application.AppSettings;
import org.nem.nac.broadcastreceivers.NotificationDeleteReceiver;
import org.nem.nac.common.KeyValuePair;
import org.nem.nac.common.Stopwatch;
import org.nem.nac.common.ThreadPoolExecutorFactory;
import org.nem.nac.common.enums.LastTransactionType;
import org.nem.nac.common.enums.TransactionType;
import org.nem.nac.common.exceptions.NoNetworkException;
import org.nem.nac.common.utils.CollectionUtils;
import org.nem.nac.common.utils.ErrorUtils;
import org.nem.nac.datamodel.NacPersistenceRuntimeException;
import org.nem.nac.datamodel.repositories.AccountRepository;
import org.nem.nac.datamodel.repositories.LastTransactionRepository;
import org.nem.nac.http.ServerErrorException;
import org.nem.nac.models.account.Account;
import org.nem.nac.models.api.transactions.TransactionMetaDataPairApiDto;
import org.nem.nac.models.primitives.AddressValue;
import org.nem.nac.models.transactions.DismissTransactionIntentExtra;
import org.nem.nac.models.transactions.LastTransaction;
import org.nem.nac.servers.ServerFinder;
import org.nem.nac.tasks.GetAccountTransactionsAsyncTask;
import org.nem.nac.ui.activities.AccountListActivity;
import org.nem.nac.ui.activities.DashboardActivity;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

public final class UpdatesNotificationService extends Service {

	private static final int                                      NOTIFICATION_ID             = 0xFC3A0001;
	private static final String                                   NOTIFICATION_GROUP_KEY      = "new-tran-notif";
	private static final int                                      CHECK_TIMEOUT_MS            = 30000;
	private final        Stopwatch                                _stopwatch                  = new Stopwatch();
	private              SoftReference<LastTransactionRepository> _lastTransactionsRepository = new SoftReference<LastTransactionRepository>(null);

	@Override
	public int onStartCommand(final Intent intent, final int flags, final int startId) {
		Timber.i("Service start: updates checker");
		_stopwatch.reset();
		_stopwatch.start();
		new Thread(() -> {
			try {
				runChecks();
			} catch (InterruptedException e) {
				Timber.w(e, "Updates check interrupted");
			} catch (NacPersistenceRuntimeException e) {
				Timber.e(e, "Updates check failed!");
			} catch (Throwable throwable) {
				Timber.e(throwable, "Updates check failed!");
				ErrorUtils.sendSilentReport("Updates check failed!", throwable);
			}
		}, getClass().getSimpleName() + "-background thread").start();
		stopSelf();
		return Service.START_NOT_STICKY;
	}

	@Override
	public IBinder onBind(final Intent intent) {
		return null;
	}

	private synchronized LastTransactionRepository getLastTransactionsRepository() {
		LastTransactionRepository repository = _lastTransactionsRepository.get();
		if (repository == null) {
			repository = new LastTransactionRepository();
			_lastTransactionsRepository = new SoftReference<>(repository);
		}
		return repository;
	}

	private void runChecks()
			throws InterruptedException {
		Timber.d("Running background updates check");
		final ThreadPoolExecutor executor = ThreadPoolExecutorFactory.createDefaultExecutor();
		final Map<AddressValue, Account> accounts = Stream.of(new AccountRepository().getAllSorted())
				.collect(Collectors.toMap(a -> a.publicData.address, a -> a));
		if (accounts.isEmpty()) {
			Timber.d("No accounts, skipping.");
			return;
		}

		if (!ServerFinder.instance().getBest().isPresent()) {
			Timber.w("No server no update check");
			return;
		}
		final Map<AddressValue, Integer> updatesPerAddress = new HashMap<>();
		final ArrayList<DismissTransactionIntentExtra> dismissTransactionExtras = new ArrayList<>();

		final List<Callable<KeyValuePair<AddressValue, Optional<List<TransactionMetaDataPairApiDto>>>>> tasks = getTasks(accounts.keySet());
		Timber.d("Starting %d tasks", tasks.size());
		final List<Future<KeyValuePair<AddressValue, Optional<List<TransactionMetaDataPairApiDto>>>>> futures =
				executor.invokeAll(tasks, CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		Timber.d("Finished tasks");

		for (Future<KeyValuePair<AddressValue, Optional<List<TransactionMetaDataPairApiDto>>>> future : futures) {
			final KeyValuePair<AddressValue, Optional<List<TransactionMetaDataPairApiDto>>> incomingTransfers;
			final List<TransactionMetaDataPairApiDto> incoming;
			try {
				incomingTransfers = future.get();
				incoming = incomingTransfers.getValue().orElse(new ArrayList<>(0));
			} catch (ExecutionException | InterruptedException | CancellationException e) {
				Timber.w(e, "Future execution failed");
				continue;
			}
			final AddressValue address = incomingTransfers.getKey();
			//
			final int unseenCount = getUnseenCount(incomingTransfers);
			final boolean haveUndismissed = haveUndismissed(incomingTransfers);
			//
			if (haveUndismissed && unseenCount > 0) {
				final DismissTransactionIntentExtra dismissTransaction =
						new DismissTransactionIntentExtra(address, incoming.isEmpty() ? null : incoming.get(0).meta.hash.data);
				updatesPerAddress.put(address, unseenCount);
				dismissTransactionExtras.add(dismissTransaction);
			}
		}
		//
		final Map<String, Integer> updatesPerName = Stream.of(updatesPerAddress)
				.collect(Collectors.toMap(u -> accounts.get(u.getKey()).name, Map.Entry::getValue));
		//
		final Intent clickIntent;
		if (updatesPerAddress.size() == 1) {
			clickIntent = new Intent(this, DashboardActivity.class)
					.putExtra(DashboardActivity.EXTRA_PARC_ACCOUNT_ADDRESS, updatesPerAddress.keySet().iterator().next());
		}
		else {
			clickIntent = new Intent(this, AccountListActivity.class);
		}
		clickIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		//
		final Intent deleteIntent = new Intent(this, NotificationDeleteReceiver.class);
		setDeleteExtras(deleteIntent, dismissTransactionExtras);
		showNotification(updatesPerName, clickIntent, deleteIntent);
	}

	private boolean haveUndismissed(final KeyValuePair<AddressValue, Optional<List<TransactionMetaDataPairApiDto>>> incomingTransfers) {
		final AddressValue address = incomingTransfers.getKey();
		final List<TransactionMetaDataPairApiDto> incoming = incomingTransfers.getValue().orElse(new ArrayList<>(0));
		final Optional<LastTransaction> lastNotified = getLastTransactionsRepository().find(address, LastTransactionType.NOTIFIED);
		if (lastNotified.isPresent()) {
			final List<TransactionMetaDataPairApiDto> newIncoming =
					CollectionUtils.getWhileNotMatch(incoming, t -> lastNotified.get().transactionHash.equals(t.meta.hash.data));
			return !newIncoming.isEmpty();
		}
		else {
			return !incoming.isEmpty();
		}
	}

	private int getUnseenCount(final KeyValuePair<AddressValue, Optional<List<TransactionMetaDataPairApiDto>>> incomingTransfers) {
		final AddressValue address = incomingTransfers.getKey();
		final List<TransactionMetaDataPairApiDto> incoming = incomingTransfers.getValue().orElse(new ArrayList<>(0));
		final Optional<LastTransaction> lastSeen = getLastTransactionsRepository().find(address, LastTransactionType.SEEN);

		if (lastSeen.isPresent()) {
			final List<TransactionMetaDataPairApiDto> newIncoming =
					CollectionUtils.getWhileNotMatch(incoming, t -> lastSeen.get().transactionHash.equals(t.meta.hash.data));
			return newIncoming.size();
		}
		else {
			return incoming.size();
		}
	}

	private void setDeleteExtras(Intent intent, ArrayList<DismissTransactionIntentExtra> transactions) {
		Bundle extras = new Bundle();
		extras.putParcelableArrayList(NotificationDeleteReceiver.EXTRA_PARC_ARR_LAST_NOTIFIED_TRANSACTIONS, transactions);
		intent.putExtras(extras);
	}

	private void showNotification(final Map<String, Integer> updates, final Intent clickIntent, final Intent deleteIntent) {
		Timber.d("showNotification(), updates: %d", updates.size());
		final NotificationManager notificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);

		final int updatesCount = Stream.of(updates.values())
				.reduce(0, (x, y) -> x + y);
		if (updatesCount == 0) {
			notificationManager.cancel(NOTIFICATION_ID);
			return;
		}

		final String lineSeparator = System.getProperty("line.separator", "\n");
		final String title = getString(R.string.notification_updates_title);
		final StringBuilder notificationSb = new StringBuilder();
		for (String name : updates.keySet()) {
			final String text = getString(R.string.notification_updates_text, name, updates.get(name));
			notificationSb.append(text).append(lineSeparator);
		}
		final String notificationText = notificationSb.toString();
//		final Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
		final NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
				.setAutoCancel(true)
//				.setSound(sound)
//				.setGroup(NOTIFICATION_GROUP_KEY)
				.setOnlyAlertOnce(true)
				.setStyle(new NotificationCompat.BigTextStyle().bigText(notificationText))
				.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
				.setSmallIcon(R.drawable.ic_notification_bar)
				.setContentTitle(title)
				.setContentText(notificationText);
		if (AppSettings.instance().getNotificationSoundEnabled()) {
			final Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
			builder.setSound(sound);
		}
		if (AppSettings.instance().getNotificationVibeEnabled()) {
			builder.setVibrate(new long[] { 0, AppHost.Vibro.MEDIUM_VIBE });
		}
		if (Build.VERSION.SDK_INT >= 21) {
			setLockScreenVisibility(builder);
		}

		final PendingIntent pendingIntent = PendingIntent.getActivity(this, new Random().nextInt(), clickIntent, PendingIntent.FLAG_UPDATE_CURRENT);
		builder.setContentIntent(pendingIntent);
		final PendingIntent deletePendingIntent = PendingIntent.getBroadcast(this, 0, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT);
		builder.setDeleteIntent(deletePendingIntent);
		//
		final Notification notification = builder.build();
		notification.flags |= Notification.FLAG_AUTO_CANCEL;
		notificationManager.notify(NOTIFICATION_ID, notification);
	}

	private List<Callable<KeyValuePair<AddressValue, Optional<List<TransactionMetaDataPairApiDto>>>>> getTasks(final Set<AddressValue> addresses) {
		return Stream.of(addresses)
				.map((addr) -> {
					//noinspection UnnecessaryLocalVariable
					final Callable<KeyValuePair<AddressValue, Optional<List<TransactionMetaDataPairApiDto>>>> task =
							new IncomingTransfersGetter(addr)::get;
					return task;
				})
				.collect(Collectors.toList());
	}

	@TargetApi(21)
	private void setLockScreenVisibility(final NotificationCompat.Builder builder) {
		builder.setVisibility(AppSettings.instance().getNotificationLockScreenEnabled() ? Notification.VISIBILITY_PUBLIC : Notification.VISIBILITY_SECRET);
	}

	private static class IncomingTransfersGetter {

		private final AddressValue _address;

		public IncomingTransfersGetter(final AddressValue address) {
			_address = address;
		}

		@NonNull
		private KeyValuePair<AddressValue, Optional<List<TransactionMetaDataPairApiDto>>> get() {
			try {
				final List<TransactionMetaDataPairApiDto> transactions = new GetAccountTransactionsAsyncTask(_address).getSynchronous();
				if (transactions == null) {
					return new KeyValuePair<>(_address, Optional.empty());
				}
				final List<TransactionMetaDataPairApiDto> transfersToMe = Stream.of(transactions)
						.filter(t -> t.transaction.unwrapTransaction().type == TransactionType.TRANSFER_TRANSACTION
								&& !t.transaction.unwrapTransaction().isSigner(_address))
						.collect(Collectors.toList());
				return new KeyValuePair<>(_address, Optional.of(transfersToMe));
			} catch (IOException | NoNetworkException | ServerErrorException e) {
				Timber.w("Failed to check for updates %s", _address);
				if (e instanceof IOException) {
					ServerFinder.instance().clearBest();
				}
				return new KeyValuePair<>(_address, Optional.empty());
			}
		}
	}
}

