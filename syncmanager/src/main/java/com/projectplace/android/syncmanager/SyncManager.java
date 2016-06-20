package com.projectplace.android.syncmanager;

import android.content.Context;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.support.annotation.WorkerThread;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Handles all sync requests that fetches and uploads data with the backend server.
 *
 * Two list are managed, a fetch list and an upload list. All syncs are executed in parallel, however all fetches will
 * be blocked as long as there is an upload ongoing. This is to prevent any conflict of happening so the database ends
 * up in an incorrect state. If a fetch is already ongoing when an upload is added the fetch will be reset and restarted.
 * <p/>
 * If a sync fetch fails it is just reported back to the listener. If an upload fails a revert of the prepare operation
 * will be made. There is extra logic to handle conflicting uploads that fails. A conflict can happen if two uploads of
 * the same type is added and one or both of them fails. The revert then need to save the correct values to the database.
 * <p/>
 * A sync thread will consume all sync requests as fast as it can on a background thread. Everything concerning the fetch
 * and upload lists needs to be synchronized with the sync lock to avoid conflicts. For example if a fetch is just finishing
 * up at the same time an upload is added, this needs to be handled so the save of the fetch is not done if the upload has
 * already executed its prepare operation.
 * <p/>
 * Listeners can be added to the sync manager to get callbacks of all kinds of sync request or a listener can be added
 * directly to a sync object. For uploads extra prepare and revert operations can be added to be able to do special logic
 * that is tied to the fragment.
 * <p/>
 * If the access token that is used to communicate with the backend needs to be refreshed then all sync objects are put
 * on hold until the token has been refreshed.
 */
public abstract class SyncManager implements SyncObject.SyncListener {
    private static final String TAG = SyncManager.class.getSimpleName();
    private boolean mLogSyncEvents;

    private final ArrayList<SyncObject> mFetchList = new ArrayList<>();
    private final ArrayList<SyncObject> mUploadList = new ArrayList<>();
    private final ArrayList<SyncObject.SyncListener> mSyncListeners = new ArrayList<>();
    private SyncObject.SyncListener mTestListener;
    protected final Context mApplicationContext;

    private final Object mSyncLock = new Object();
    private SyncThread mSyncThread;
    private boolean mUsesAccessToken = true;
    private boolean mSyncStopped;
    private boolean mTestDisableNewSyncObjects;

    public interface RefreshAccessTokenCallback {
        void accessTokenRefreshed(boolean success);
    }

    /**
     * Called just before a sync object is started to check for special conditions if the sync object
     * should be started. This is always called on a background thread.
     *
     * @param sync The sync object that is about to start.
     * @return True if the sync object should be started, otherwise False.
     */
    @WorkerThread
    protected abstract boolean shouldSyncObject(@NonNull SyncObject sync);

    /**
     * Called just before a sync object that needs an access token is started to check if the access token needs to be
     * refreshed first. If that is needed, {@link SyncManager#startRefreshAccessToken(RefreshAccessTokenCallback callback)}
     * will be called directly and the sync object will be set on hold until that is done. This is always called on a
     * background thread.
     *
     * @return True if the access token needs to be refreshed first.
     */
    @WorkerThread
    protected abstract boolean shouldRefreshAccessToken();

    /**
     * Called when the access token needs to be refreshed. This is always called on a background thread.
     *
     * @param callback to be used to indicate that a refresh was successful or not. If the refresh failed
     *                 another try will take place directly after.
     */
    @WorkerThread
    protected abstract void startRefreshAccessToken(@NonNull RefreshAccessTokenCallback callback);

    protected SyncManager(@NonNull Context context) {
        mApplicationContext = context.getApplicationContext();
    }

    private void startSync() {
        synchronized (mSyncLock) {
            mSyncStopped = false;
            if (mSyncThread == null) {
                mSyncThread = new SyncThread();
                mSyncThread.start();
            }
            mSyncLock.notify();
        }
    }

    public void stopSync() {
        synchronized (mSyncLock) {
            mSyncStopped = true;
            mFetchList.clear();
            mUploadList.clear();
            mSyncLock.notify();
            mSyncThread = null;
        }
    }

    // For test purposes only
    @VisibleForTesting
    public void setTestListener(SyncObject.SyncListener testListener) {
        mTestListener = testListener;
    }

    // For test purposes only
    @VisibleForTesting
    public SyncObject.SyncListener getTestListener() {
        return mTestListener;
    }

    // For test purposes only
    @VisibleForTesting
    public void setTestDisableNewSyncObjects(boolean disable) {
        mTestDisableNewSyncObjects = disable;
    }

    public void setLogsEnabled(boolean enabled) {
        mLogSyncEvents = enabled;
    }

    /**
     * The sync manager is designed to use an oauth access token flow by default, but
     * you can disable this and the sync manager will ignore any access token handling.
     *
     * @param usesAccessToken False if the sync manager should ignore access token handling. Default is True.
     */
    public void setUsesAccessToken(boolean usesAccessToken) {
        mUsesAccessToken = usesAccessToken;
    }

    /**
     * Sets a listener to get callbacks when sync objects are finished.
     *
     * @see {@link com.projectplace.android.syncmanager.SyncObject.SyncListener}
     */
    public void registerSyncListener(@NonNull SyncObject.SyncListener listener) {
        if (mSyncListeners.indexOf(listener) == -1) {
            mSyncListeners.add(listener);
        }
    }

    /**
     * Unregisters a sync listener.
     *
     * @see {@link SyncObject.SyncListener}
     */
    public void unregisterSyncListener(@NonNull SyncObject.SyncListener listener) {
        mSyncListeners.remove(listener);
    }

    private boolean containsIdenticalFetch(@NonNull SyncFetch fetch) {
        synchronized (mSyncLock) {
            for (int i = 0; i < mFetchList.size(); i++) {
                SyncFetch tmpFetch = (SyncFetch) mFetchList.get(i);
                if (tmpFetch.willFetchSameData(fetch)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Adds a fetch object to the sync queue. The fetch will be started as soon as possible.
     * If an upload object is running it will hold until that is finished first.
     *
     * @see {@link SyncFetch}
     */
    public void fetch(@NonNull SyncFetch newFetch) {
        if (!mTestDisableNewSyncObjects) {
            synchronized (mSyncLock) {
                syncLog("(Fetch) New " + newFetch.getClass().getSimpleName());
                // If there already exists an identical fetch object in the fetch list then don't add it to gain performance.
                if (!containsIdenticalFetch(newFetch)) {
                    newFetch.setManagerSyncListener(this);
                    mFetchList.add(newFetch);
                    startSync();
                } else {
                    syncLog("(Fetch) Equal fetch object found, don't add");
                }
            }
        }
    }

    private void resetFetches(@NonNull SyncUpload newUpload) {
        for (SyncObject fetch : mFetchList) {
            if (newUpload.shouldResetFetch((SyncFetch) fetch)) {
                ((SyncFetch) fetch).setShouldReset();
            }
        }
    }

    /**
     * Adds an upload sync to the sync queue. The upload will be started as soon as possible.
     *
     * @see {@link SyncUpload}
     */
    public void upload(@NonNull final SyncUpload newUpload) {
        if (!mTestDisableNewSyncObjects) {
            synchronized (mSyncLock) {
                syncLog("(Upload) New " + newUpload.getClass().getSimpleName());
                newUpload.setManagerSyncListener(this);
                // Reset all fetches so they are restarted as they might conflict with the upload
                resetFetches(newUpload);

                // If there already exists an upload of the same type, then the revert values needs to be updated
                for (SyncObject upload : mUploadList) {
                    newUpload.updateRevertValues((SyncUpload) upload, true);
                }

                // Task has to be serial so the db operations comes in the correct order
                new AsyncTask<Void, Void, Void>() {
                    @Override
                    protected Void doInBackground(Void... params) {
                        newUpload.prepare();
                        // Lock as we are manipulating with the upload list
                        synchronized (mSyncLock) {
                            mUploadList.add(newUpload);
                        }
                        startSync();
                        return null;
                    }
                }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
            }
        }
    }

    /**
     * Internal callback when an upload is finished. This should never be called from outside of the sync manager.
     */
    @Override
    public void onUploadDone(@NonNull final SyncUpload syncUpload) {
        synchronized (mSyncLock) {
            syncLog("(onUploadDone) " + syncUpload.getClass().getSimpleName());
            mUploadList.remove(syncUpload);

            // Need to check for conflicts outside the async task as it might be to late in doInBackground
            boolean conflict = false;
            if (syncUpload.isFailed()) {
                // Check if there are any conflicting uploads in the queue, if there are we should not revert as
                // that would overwrite the prepare operations of that upload.
                for (SyncObject upload : mUploadList) {
                    conflict = ((SyncUpload) upload).hasConflict(syncUpload);
                }
                showError(syncUpload);
            }
            final boolean shouldRevertIfFailed = !conflict;

            // Task has to be serial so the db operations comes in the correct order
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    if (syncUpload.isFailed()) {
                        if (shouldRevertIfFailed) {
                            syncLog("(onUploadDone) Failed to upload, reverting");
                            syncUpload.revert();
                        } else {
                            syncLog("(onUploadDone) Failed to upload but no revert as there is another conflicting upload ongoing");
                        }
                    } else {
                        // Lock as we are iterating the upload list
                        synchronized (mSyncLock) {
                            // Update any existing uploads of the same type with new revert values as this upload succeeded
                            for (SyncObject upload : mUploadList) {
                                ((SyncUpload) upload).updateRevertValues(syncUpload, false);
                            }
                        }
                        syncUpload.onSave();
                    }

                    if (mTestListener != null) {
                        mTestListener.onUploadDone(syncUpload);
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {
                    for (SyncObject.SyncListener listener : mSyncListeners) {
                        listener.onUploadDone(syncUpload);
                    }
                    if (syncUpload.getSyncListener() != null) {
                        syncUpload.getSyncListener().onUploadDone(syncUpload);
                    }
                }
            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
        }
        startSync();
    }

    /**
     * Internal callback when a fetch is finished. This should never be called from outside of the sync manager.
     */
    @Override
    public void onFetchDone(@NonNull final SyncFetch syncFetch) {
        // If sync is stopped we should not save anything
        if (mSyncStopped) {
            return;
        }

        syncLog("(onFetchDone) " + syncFetch.getClass().getSimpleName());
        if (syncFetch.isFailed()) {
            // If a fetch has failed, just remove it an tell the listeners.
            // Or if it has any retries left then reset and try again.
            if (syncFetch.getRetries() != 0) {
                syncLog("(onFetchDone) SyncFetch failed, retrying. Retries left: " + (syncFetch.getRetries() - 1));
                syncFetch.reset();
                syncFetch.setRetries(syncFetch.getRetries() - 1);
                startSync();
            } else {
                synchronized (mSyncLock) {
                    mFetchList.remove(syncFetch);
                }
                showError(syncFetch);
                if (mTestListener != null) {
                    mTestListener.onFetchDone(syncFetch);
                }
                for (SyncObject.SyncListener listener : mSyncListeners) {
                    listener.onFetchDone(syncFetch);
                }
                if (syncFetch.getSyncListener() != null) {
                    syncFetch.getSyncListener().onFetchDone(syncFetch);
                }
            }
        } else {
            synchronized (mSyncLock) {
                // When a fetch is successful first check if it is in conflict with any upload.
                if (!syncFetch.shouldReset()) {
                    mFetchList.remove(syncFetch);

                    // Task has to be serial so the db operations comes in the correct order
                    new AsyncTask<Void, Void, Void>() {
                        @Override
                        protected Void doInBackground(Void... params) {
                            syncFetch.onSave();
                            // Test listener needs to be called on background thread
                            if (mTestListener != null) {
                                mTestListener.onFetchDone(syncFetch);
                            }
                            return null;
                        }

                        @Override
                        protected void onPostExecute(Void isDone) {
                            for (SyncObject.SyncListener listener : mSyncListeners) {
                                listener.onFetchDone(syncFetch);
                            }
                            if (syncFetch.getSyncListener() != null) {
                                syncFetch.getSyncListener().onFetchDone(syncFetch);
                            }
                        }
                    }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
                } else {
                    syncLog("(onFetchDone) A conflict with an upload occurred, reset and the fetch will be done again: " + syncFetch.getClass().getSimpleName());
                    // A conflict with an upload occurred, reset and the fetch will be done again
                    syncFetch.reset();
                    startSync();
                }
            }
        }
        syncLog("(onFetchDone) FetchList size: " + mFetchList.size());
    }

    /**
     * By default a toast with the error message will be shown. This can however
     * be overridden and a custom error can be displayed instead.
     *
     * @param syncObject The sync object which has failed for some reason.
     */
    protected void showError(@NonNull SyncObject syncObject) {
        if (syncObject.getErrorMessage() != null) {
            Toast.makeText(mApplicationContext, syncObject.getErrorMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The sync thread will start sync objects as soon as it is added to the sync manager. However, fetch objects will
     * not be started until all upload objects has finished. This is because we want to minimize the risk of conflicts
     * between uploads and fetches. If no sync objects exists the sync thread pauses and waits for a new sync object.
     */
    private class SyncThread extends Thread {
        private boolean mRefreshingAccessToken;

        @Override
        public void run() {
            while (!mSyncStopped) {
                syncLog("Sync Thread - Check for sync object");
                synchronized (mSyncLock) {
                    SyncObject syncObject = getNextSyncObject();
                    if (syncObject != null && !shouldSyncObject(syncObject)) {
                        syncLog("Sync Thread - Should not sync object, remove without callback");
                        if (syncObject instanceof SyncFetch) {
                            mFetchList.remove(syncObject);
                        } else {
                            mUploadList.remove(syncObject);
                        }
                    } else if (mUsesAccessToken && syncObject != null && syncObject.needsAccessToken() && shouldRefreshAccessToken()) {
                        syncLog("Sync Thread - Access token needs to be refreshed");
                        refreshAccessToken();
                    } else {
                        if (syncObject != null) {
                            syncLog("Sync Thread - Start sync object: " + syncObject.getClass().getSimpleName());
                            syncObject.start();
                        }

                        if (!mSyncStopped && getNextSyncObject() == null) {
                            try {
                                syncLog("Sync Thread - Wait for sync objects");
                                mSyncLock.wait();
                            } catch (InterruptedException e) {
                                syncLog("Sync Thread - Interrupted");
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }

        private void refreshAccessToken() {
            if (!mRefreshingAccessToken) {
                syncLog("Sync Thread - Refreshing access token");
                mRefreshingAccessToken = true;
                startRefreshAccessToken(new RefreshAccessTokenCallback() {
                    @Override
                    public void accessTokenRefreshed(boolean success) {
                        syncLog("Sync Thread - Access token refresh done. Success = " + success);
                        synchronized (mSyncLock) {
                            mSyncLock.notify();
                        }
                        mRefreshingAccessToken = false;
                    }
                });
            } else {
                syncLog("Sync Thread - Refresh access token already in progress");
            }

            if (mRefreshingAccessToken) {
                try {
                    syncLog("Sync Thread - Wait for refresh access token to finish");
                    mSyncLock.wait();
                } catch (InterruptedException e) {
                    syncLog("Sync Thread - Interrupted");
                    e.printStackTrace();
                }
            }
        }

        /**
         * @return the next sync object to start. Fetch objects can only be started if no upload objects exists.
         */
        private SyncObject getNextSyncObject() {
            synchronized (mSyncLock) {
                for (SyncObject upload : mUploadList) {
                    if (!upload.isStarted()) {
                        return upload;
                    }
                }
                if (mUploadList.size() == 0) {
                    for (SyncObject fetch : mFetchList) {
                        if (!fetch.isStarted()) {
                            return fetch;
                        }
                    }
                }
            }
            return null;
        }
    }

    private void syncLog(@NonNull String message) {
        if (mLogSyncEvents) {
            Log.d(TAG, message);
        }
    }
}
