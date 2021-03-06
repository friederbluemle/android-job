/*
 * Copyright 2007-present Evernote Corporation.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.evernote.android.job;

import android.app.AlarmManager;
import android.content.ContentValues;
import android.database.Cursor;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import com.evernote.android.job.util.JobApi;
import com.evernote.android.job.util.JobCat;
import com.evernote.android.job.util.JobPreconditions;
import com.evernote.android.job.util.JobUtil;
import com.evernote.android.job.util.support.PersistableBundleCompat;

import net.vrallev.android.cat.CatLog;

import java.util.concurrent.TimeUnit;

/**
 * Holds information about the pending {@link Job}. Use the {@link Builder} to create an instance.
 * Once built you can either call {@link #schedule()} or {@link JobManager#schedule(JobRequest)}
 * to schedule the request.
 *
 * @author rwondratschek
 */
public final class JobRequest {

    /**
     * If you don't change the back-off ms, then 30 seconds are used as default.
     * @see Builder#setBackoffCriteria(long, BackoffPolicy)
     */
    public static final long DEFAULT_BACKOFF_MS = 30_000L;

    /**
     * If you don't change the back-off policy, then EXPONENTIAL is used as default.
     * @see Builder#setBackoffCriteria(long, BackoffPolicy)
     */
    public static final BackoffPolicy DEFAULT_BACKOFF_POLICY = BackoffPolicy.EXPONENTIAL;

    /**
     * If you don't change the required network type, then no connection is required.
     * @see Builder#setRequiredNetworkType(NetworkType)
     * @see Builder#setRequirementsEnforced(boolean)
     */
    public static final NetworkType DEFAULT_NETWORK_TYPE = NetworkType.ANY;

    private static final CatLog CAT = new JobCat("JobRequest");

    private final Builder mBuilder;
    private final JobApi mJobApi;

    private int mNumFailures;
    private long mScheduledAt;

    private JobRequest(Builder builder) {
        mBuilder = builder;
        mJobApi = builder.mExact ? JobApi.V_14 : JobManager.instance().getApi();
    }

    /**
     * @return The unique ID for this job.
     */
    public int getJobId() {
        return mBuilder.mId;
    }

    /**
     * @return The tag which is used to map this request to a specific {@link Job}.
     */
    @NonNull
    public String getTag() {
        return mBuilder.mTag;
    }

    /**
     * Only valid if the job isn't periodic.
     *
     * @return The start of the time frame when the job will run after it's been scheduled.
     */
    public long getStartMs() {
        return mBuilder.mStartMs;
    }

    /**
     * Only valid if the job isn't periodic.
     *
     * @return The end of the time frame when the job will run after it's been scheduled.
     */
    public long getEndMs() {
        return mBuilder.mEndMs;
    }

    /**
     * Only valid if the job isn't periodic.
     *
     * @return The back-off policy if a job failed and is rescheduled.
     */
    public BackoffPolicy getBackoffPolicy() {
        return mBuilder.mBackoffPolicy;
    }

    /**
     * Only valid if the job isn't periodic.
     *
     * @return The initial back-off time which is increasing depending on the {@link #getBackoffPolicy()}
     * if the job fails multiple times.
     */
    public long getBackoffMs() {
        return mBuilder.mBackoffMs;
    }

    /**
     * @return Whether this job is periodic.
     */
    public boolean isPeriodic() {
        return getIntervalMs() > 0;
    }

    /**
     * Only valid if the job is periodic.
     *
     * @return The interval in which the job runs once.
     */
    public long getIntervalMs() {
        return mBuilder.mIntervalMs;
    }

    /**
     * @return If {@code true}, then all requirements are checked before the job runs. If one requirement
     * isn't met, then the job is rescheduled right away.
     */
    public boolean requirementsEnforced() {
        return mBuilder.mRequirementsEnforced;
    }

    /**
     * @return If {@code true}, then the job should only run if the device is charging.
     */
    public boolean requiresCharging() {
        return mBuilder.mRequiresCharging;
    }

    /**
     * @return If {@code true}, then job should only run if the device is idle.
     */
    public boolean requiresDeviceIdle() {
        return mBuilder.mRequiresDeviceIdle;
    }

    /**
     * @return The network state which is required to run the job.
     */
    public NetworkType requiredNetworkType() {
        return mBuilder.mNetworkType;
    }

    /**
     * @return The extras for this job.
     */
    public PersistableBundleCompat getExtras() {
        if (mBuilder.mExtras == null && !TextUtils.isEmpty(mBuilder.mExtrasXml)) {
            mBuilder.mExtras = PersistableBundleCompat.fromXml(mBuilder.mExtrasXml);
        }
        return mBuilder.mExtras;
    }

    /**
     * @return If {@code true}, then the job persists across reboots.
     */
    public boolean isPersisted() {
        return mBuilder.mPersisted;
    }

    /**
     * @return If {@code true}, then the job will run at exact time ignoring the device state.
     */
    public boolean isExact() {
        return mBuilder.mExact;
    }

    /*package*/ long getBackoffOffset() {
        if (isPeriodic()) {
            return 0L;
        }

        long offset;
        switch (getBackoffPolicy()) {
            case LINEAR:
                offset = mNumFailures * getBackoffMs();
                break;

            case EXPONENTIAL:
                if (mNumFailures == 0) {
                    offset = 0L;
                } else {
                    offset = (long) (getBackoffMs() * Math.pow(2, mNumFailures - 1));
                }
                break;

            default:
                throw new IllegalStateException("not implemented");
        }

        return Math.min(offset, TimeUnit.HOURS.toMillis(5)); // use max of 5 hours like JobScheduler
    }

    /*package*/ JobApi getJobApi() {
        return mJobApi;
    }

    /*package*/ void setScheduledAt(long timeStamp) {
        mScheduledAt = timeStamp;
    }

    /*package*/ long getScheduledAt() {
        return mScheduledAt;
    }

    /*package*/ int getNumFailures() {
        return mNumFailures;
    }

    /**
     * Convenience method. Internally it calls {@link JobManager#schedule(JobRequest)}
     * and {@link #getJobId()} for this request.
     *
     * @return The unique ID for this job.
     */
    public int schedule() {
        JobManager.instance().schedule(this);
        return getJobId();
    }

    /**
     * Cancel this request if it has been scheduled. Note that if the job isn't periodic, then the
     * time passed since the job has been scheduled is subtracted from the time frame. For example
     * a job should run between 4 and 6 seconds from now. You cancel the scheduled job after 2
     * seconds, then the job will run between 2 and 4 seconds after it's been scheduled again.
     *
     * @return A builder to modify the parameters.
     */
    public Builder cancelAndEdit() {
        JobManager.instance().cancel(getJobId());
        Builder builder = new Builder(this, false);

        if (!isPeriodic()) {
            long offset = System.currentTimeMillis() - mScheduledAt;
            long minValue = 1L; // 1ms
            builder.setExecutionWindow(Math.max(minValue, getStartMs() - offset), Math.max(minValue, getEndMs() - offset));
        }

        return builder;
    }

    /*package*/ int reschedule(boolean failure) {
        JobRequest newRequest = new Builder(this, true).build();
        if (failure) {
            newRequest.mNumFailures = mNumFailures + 1;
        }
        return newRequest.schedule();
    }

    /*package*/ void incNumFailures() {
        mNumFailures++;
        ContentValues contentValues = new ContentValues();
        contentValues.put(JobStorage.COLUMN_NUM_FAILURES, mNumFailures);
        JobManager.instance().getJobStorage().update(this, contentValues);
    }

    /*package*/ ContentValues toContentValues() {
        ContentValues contentValues = new ContentValues();
        mBuilder.fillContentValues(contentValues);
        contentValues.put(JobStorage.COLUMN_NUM_FAILURES, mNumFailures);
        contentValues.put(JobStorage.COLUMN_SCHEDULED_AT, mScheduledAt);
        return contentValues;
    }

    /*package*/ static JobRequest fromCursor(Cursor cursor) throws Exception {
        JobRequest request = new Builder(cursor).build();
        request.mNumFailures = cursor.getInt(cursor.getColumnIndex(JobStorage.COLUMN_NUM_FAILURES));
        request.mScheduledAt = cursor.getLong(cursor.getColumnIndex(JobStorage.COLUMN_SCHEDULED_AT));

        JobPreconditions.checkArgumentNonnegative(request.mNumFailures, "failure count can't be negative");
        JobPreconditions.checkArgumentNonnegative(request.mScheduledAt, "scheduled at can't be negative");

        return request;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        JobRequest request = (JobRequest) o;

        return mBuilder.equals(request.mBuilder);
    }

    @Override
    public int hashCode() {
        return mBuilder.hashCode();
    }

    @Override
    public String toString() {
        return "request{id=" + getJobId() + ", tag=" + getTag() + '}';
    }

    /**
     * Builder class for constructing JobRequests.
     */
    public static final class Builder {

        private final int mId;
        private final String mTag;

        private long mStartMs;
        private long mEndMs;

        private long mBackoffMs;
        private BackoffPolicy mBackoffPolicy;

        private long mIntervalMs;

        private boolean mRequirementsEnforced;
        private boolean mRequiresCharging;
        private boolean mRequiresDeviceIdle;
        private boolean mExact;
        private NetworkType mNetworkType;

        private PersistableBundleCompat mExtras;
        private String mExtrasXml;

        private boolean mPersisted;

        /**
         * Creates a new instance to build a {@link JobRequest}. Note that the {@code tag} doesn't
         * need to be unique. Each created request has an unique ID to differentiate between jobs
         * with the same tag.
         *
         * <br>
         * <br>
         *
         * When your job is about to start you receive a callback in your {@link JobCreator} to create
         * a {@link Job} for this {@code tag}.
         *
         * @param tag The tag is used to identify your {@code Job} in {@link JobCreator#create(String)}.
         */
        public Builder(@NonNull String tag) {
            mTag = JobPreconditions.checkNotEmpty(tag);
            mId = JobManager.instance().getJobStorage().nextJobId();

            mStartMs = -1;
            mEndMs = -1;

            mBackoffMs = DEFAULT_BACKOFF_MS;
            mBackoffPolicy = DEFAULT_BACKOFF_POLICY;

            mNetworkType = DEFAULT_NETWORK_TYPE;
        }

        private Builder(JobRequest request, boolean createId) {
            mId = createId ? JobManager.instance().getJobStorage().nextJobId() : request.getJobId();
            mTag = request.getTag();

            mStartMs = request.getStartMs();
            mEndMs = request.getEndMs();

            mBackoffMs = request.getBackoffMs();
            mBackoffPolicy = request.getBackoffPolicy();

            mIntervalMs = request.getIntervalMs();

            mRequirementsEnforced = request.requirementsEnforced();
            mRequiresCharging = request.requiresCharging();
            mRequiresDeviceIdle = request.requiresDeviceIdle();
            mExact = request.isExact();
            mNetworkType = request.requiredNetworkType();

            mExtras = request.mBuilder.mExtras;
            mExtrasXml = request.mBuilder.mExtrasXml;
            mPersisted = request.isPersisted();
        }

        @SuppressWarnings("unchecked")
        private Builder(Cursor cursor) throws Exception {
            mId = cursor.getInt(cursor.getColumnIndex(JobStorage.COLUMN_ID));
            mTag = cursor.getString(cursor.getColumnIndex(JobStorage.COLUMN_TAG));

            mStartMs = cursor.getLong(cursor.getColumnIndex(JobStorage.COLUMN_START_MS));
            mEndMs = cursor.getLong(cursor.getColumnIndex(JobStorage.COLUMN_END_MS));

            mBackoffMs = cursor.getLong(cursor.getColumnIndex(JobStorage.COLUMN_BACKOFF_MS));
            try {
                mBackoffPolicy = BackoffPolicy.valueOf(cursor.getString(cursor.getColumnIndex(JobStorage.COLUMN_BACKOFF_POLICY)));
            } catch (Throwable t) {
                CAT.e(t); // https://gist.github.com/vRallev/574563f0e3fe636b19a7
                mBackoffPolicy = DEFAULT_BACKOFF_POLICY;
            }

            mIntervalMs = cursor.getLong(cursor.getColumnIndex(JobStorage.COLUMN_INTERVAL_MS));

            mRequirementsEnforced = cursor.getInt(cursor.getColumnIndex(JobStorage.COLUMN_REQUIREMENTS_ENFORCED)) > 0;
            mRequiresCharging = cursor.getInt(cursor.getColumnIndex(JobStorage.COLUMN_REQUIRES_CHARGING)) > 0;
            mRequiresDeviceIdle = cursor.getInt(cursor.getColumnIndex(JobStorage.COLUMN_REQUIRES_DEVICE_IDLE)) > 0;
            mExact = cursor.getInt(cursor.getColumnIndex(JobStorage.COLUMN_EXACT)) > 0;
            try {
                mNetworkType = NetworkType.valueOf(cursor.getString(cursor.getColumnIndex(JobStorage.COLUMN_NETWORK_TYPE)));
            } catch (Throwable t) {
                CAT.e(t); // https://gist.github.com/vRallev/574563f0e3fe636b19a7
                mNetworkType = DEFAULT_NETWORK_TYPE;
            }

            mExtrasXml = cursor.getString(cursor.getColumnIndex(JobStorage.COLUMN_EXTRAS));

            mPersisted = cursor.getInt(cursor.getColumnIndex(JobStorage.COLUMN_PERSISTED)) > 0;
        }

        private void fillContentValues(ContentValues contentValues) {
            contentValues.put(JobStorage.COLUMN_ID, mId);
            contentValues.put(JobStorage.COLUMN_TAG, mTag);

            contentValues.put(JobStorage.COLUMN_START_MS, mStartMs);
            contentValues.put(JobStorage.COLUMN_END_MS, mEndMs);

            contentValues.put(JobStorage.COLUMN_BACKOFF_MS, mBackoffMs);
            contentValues.put(JobStorage.COLUMN_BACKOFF_POLICY, mBackoffPolicy.toString());

            contentValues.put(JobStorage.COLUMN_INTERVAL_MS, mIntervalMs);

            contentValues.put(JobStorage.COLUMN_REQUIREMENTS_ENFORCED, mRequirementsEnforced);
            contentValues.put(JobStorage.COLUMN_REQUIRES_CHARGING, mRequiresCharging);
            contentValues.put(JobStorage.COLUMN_REQUIRES_DEVICE_IDLE, mRequiresDeviceIdle);
            contentValues.put(JobStorage.COLUMN_EXACT, mExact);
            contentValues.put(JobStorage.COLUMN_NETWORK_TYPE, mNetworkType.toString());

            if (mExtras != null) {
                contentValues.put(JobStorage.COLUMN_EXTRAS, mExtras.saveToXml());
            } else if (!TextUtils.isEmpty(mExtrasXml)) {
                contentValues.put(JobStorage.COLUMN_EXTRAS, mExtrasXml);
            }
            contentValues.put(JobStorage.COLUMN_PERSISTED, mPersisted);
        }

        /**
         * Set the time window when the job will be run. Note that it's mandatory to set a time for
         * one-off tasks, but it's not allowed to call this method together with
         * {@link #setPeriodic(long)} or {@link #setExact(long)}. For those types jobs it doesn't
         * make sense to have a time window.
         *
         * @param startMs Earliest point from which your task is eligible to run.
         * @param endMs Latest point at which your task must be run.
         */
        public Builder setExecutionWindow(long startMs, long endMs) {
            mStartMs = JobPreconditions.checkArgumentPositive(startMs, "startMs must be greater than 0");
            mEndMs = JobPreconditions.checkArgumentInRange(endMs, startMs, Long.MAX_VALUE, "endMs");
            return this;
        }

        /**
         * Set optional extras. This is persisted, so only primitive types are allowed.
         *
         * @param extras Bundle containing extras which you can retrieve with {@link Job.Params#getExtras()}.
         */
        public Builder setExtras(@Nullable PersistableBundleCompat extras) {
            if (extras == null) {
                mExtras = null;
                mExtrasXml = null;
            } else {
                mExtras = new PersistableBundleCompat(extras);
            }
            return this;
        }

        /**
         * It's possible to set several requirements for a job, however, not all of them need to
         * be considered by the underlying {@link JobApi}. If the requirements are enforced, then
         * the device state is checked before your job runs. If at least one requirement isn't met,
         * then the job is rescheduled and not run.
         *
         * <br>
         * <br>
         *
         * It's possible to check single requirements in your job, if you keep this field set to
         * {@code false}. The {@link Job} class provides several methods, e.g.
         * {@link Job#isRequirementChargingMet()}.
         *
         * <br>
         * <br>
         *
         * Note that it's not allowed to set requirements for exact jobs. That wouldn't make sense,
         * because the job needs to run at a specific time no matter of the device's state.
         *
         * <br>
         * <br>
         *
         * The default value is set to {@code false}.
         *
         * @param enforced If {@code true}, then all set requirements are manually checked.
         */
        public Builder setRequirementsEnforced(boolean enforced) {
            mRequirementsEnforced = enforced;
            return this;
        }

        /**
         * Set some description of the kind of network type your job needs to have.
         * Not calling this function means the network is not necessary, as the default is
         * {@link NetworkType#ANY}.
         *
         * <br>
         * <br>
         *
         * Note that if the deadline is met and the requirements aren't enforced, then your job
         * will run and ignore this requirement.
         *
         * @param networkType The required network type.
         * @see #setRequirementsEnforced(boolean)
         * @see #setExecutionWindow(long, long)
         */
        public Builder setRequiredNetworkType(@Nullable NetworkType networkType) {
            mNetworkType = networkType;
            return this;
        }

        /**
         * Specify that to run this job, the device needs to be plugged in. The default is set
         * to {@code false}.
         *
         * <br>
         * <br>
         *
         * Note that if the deadline is met and the requirements aren't enforced, then your job
         * will run and ignore this requirement.
         *
         * @param requiresCharging Whether or not the device needs to be plugged in.
         * @see #setRequirementsEnforced(boolean)
         * @see #setExecutionWindow(long, long)
         */
        public Builder setRequiresCharging(boolean requiresCharging) {
            mRequiresCharging = requiresCharging;
            return this;
        }

        /**
         * Specify that to run, the job needs the device to be in idle mode. This defaults to
         * {@code false}. Idle mode is a loose definition provided by the system, which means that the device
         * is not in use, and has not been in use for some time. As such, it is a good time to
         * perform resource heavy jobs.
         *
         * <br>
         * <br>
         *
         * Note that if the deadline is met and the requirements aren't enforced, then your job
         * will run and ignore this requirement.
         *
         * @param requiresDeviceIdle Whether or not the device needs be idle.
         * @see #setRequirementsEnforced(boolean)
         * @see #setExecutionWindow(long, long)
         */
        public Builder setRequiresDeviceIdle(boolean requiresDeviceIdle) {
            mRequiresDeviceIdle = requiresDeviceIdle;
            return this;
        }

        /**
         * Specify that the job should run at an exact time. This type of job must only be used
         * for situations where it is actually required that the alarm go off even while in idle.
         * A reasonable example would be for a calendar notification that should make a sound so
         * the user is aware of it.
         *
         * <br>
         * <br>
         *
         * Note that an exact job can't be periodic. It's also not allowed to specify any requirement,
         * the exact timing is the most important requirement for such a job. This method overrides
         * any specified time window.
         *
         * <br>
         * <br>
         *
         * The default value is set to {@code false}. Internally an exact job is always using the
         * {@link AlarmManager}.
         *
         * @param exactMs The exact offset when the job should run from when the job was scheduled.
         * @see AlarmManager#setExact(int, long, android.app.PendingIntent)
         * @see AlarmManager#setExactAndAllowWhileIdle(int, long, android.app.PendingIntent)
         */
        public Builder setExact(long exactMs) {
            mExact = true;
            return setExecutionWindow(exactMs, exactMs);
        }

        /**
         * This job should run one time during each interval. As default a job isn't periodic.
         *
         * <br>
         * <br>
         *
         * It isn't allowed to specify a time window for a periodic job. Instead you set an interval
         * with this function. Since {@link Job.Result#RESCHEDULE} is ignored for periodic jobs,
         * setting a back-off criteria is illegal as well.
         *
         * @param intervalMs The job should at most once every {@code intervalMs}.
         */
        public Builder setPeriodic(long intervalMs) {
            mIntervalMs = JobPreconditions.checkArgumentInRange(intervalMs, 60_000L, Long.MAX_VALUE, "intervalMs");
            return this;
        }

        /**
         * Change the back-off policy for a non periodic job. The default value is set to 30 seconds
         * and {@link BackoffPolicy#EXPONENTIAL}. The time is increasing each time a job fails and
         * returns {@link Job.Result#RESCHEDULE}, but capped at 5 hours.
         *
         * <br>
         * <br>
         *
         * Note that it's not allowed to change the back-off criteria for a periodic job.
         *
         * @param backoffMs The initial interval to wait when the job has been rescheduled.
         * @param backoffPolicy Is either {@link BackoffPolicy#LINEAR} or {@link BackoffPolicy#EXPONENTIAL}.
         * @see Job.Result#RESCHEDULE
         * @see Job#onReschedule(int)
         */
        public Builder setBackoffCriteria(long backoffMs, @NonNull BackoffPolicy backoffPolicy) {
            mBackoffMs = JobPreconditions.checkArgumentPositive(backoffMs, "backoffMs must be > 0");
            mBackoffPolicy = JobPreconditions.checkNotNull(backoffPolicy);
            return this;
        }

        /**
         * Set whether the job should be persisted across reboots. This will only have an
         * effect if your application holds the permission
         * {@link android.Manifest.permission#RECEIVE_BOOT_COMPLETED}. Otherwise an exception will
         * be thrown. The default is set to {@code false}.
         *
         * @param persisted If {@code true} the job is scheduled after a reboot.
         */
        public Builder setPersisted(boolean persisted) {
            if (!JobUtil.hasBootPermission(JobManager.instance().getContext())) {
                throw new IllegalStateException("Does not have RECEIVE_BOOT_COMPLETED permission, which is mandatory for this feature");
            }
            mPersisted = persisted;
            return this;
        }

        /**
         * @return The {@link JobRequest} with this parameters to hand to the {@link JobManager}.
         */
        public JobRequest build() {
            JobPreconditions.checkArgumentNonnegative(mId, "id can't be negative");
            JobPreconditions.checkNotEmpty(mTag);
            JobPreconditions.checkArgumentPositive(mBackoffMs, "backoffMs must be > 0");
            JobPreconditions.checkNotNull(mBackoffPolicy);
            JobPreconditions.checkNotNull(mNetworkType);

            if (mIntervalMs > 0) {
                JobPreconditions.checkArgumentInRange(mIntervalMs, 60_000L, Long.MAX_VALUE, "intervalMs");
            }

            if (mExact && mIntervalMs > 0) {
                throw new IllegalArgumentException("Can't call setExact() on a periodic job.");
            }
            if (mExact && mStartMs != mEndMs) {
                throw new IllegalArgumentException("Can't call setExecutionWindow() for an exact job.");
            }
            if (mExact && (mRequirementsEnforced || mRequiresDeviceIdle || mRequiresCharging || !DEFAULT_NETWORK_TYPE.equals(mNetworkType))) {
                throw new IllegalArgumentException("Can't require any condition for an exact job.");
            }

            if (mIntervalMs <= 0 && (mStartMs == -1 || mEndMs == -1)) {
                throw new IllegalArgumentException("You're trying to build a job with no constraints, this is not allowed.");
            }
            if (mIntervalMs > 0 && (mStartMs != -1 || mEndMs != -1)) {
                throw new IllegalArgumentException("Can't call setExecutionWindow() on a periodic job.");
            }
            if (mIntervalMs > 0 && (mBackoffMs != DEFAULT_BACKOFF_MS || !DEFAULT_BACKOFF_POLICY.equals(mBackoffPolicy))) {
                throw new IllegalArgumentException("A periodic job will not respect any back-off policy, so calling "
                        + "setBackoffCriteria() with setPeriodic() is an error.");
            }

            return new JobRequest(this);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Builder builder = (Builder) o;

            return mId == builder.mId;
        }

        @Override
        public int hashCode() {
            return mId;
        }
    }

    public enum BackoffPolicy {
        /**
         * backoff = numFailures * initial_backoff.
         */
        LINEAR,
        /**
         * backoff = initial_backoff * 2 ^ (numFailures - 1).
         */
        EXPONENTIAL
    }

    public enum NetworkType {
        /**
         * Network must not be connected.
         */
        ANY,
        /**
         * Network must be connected.
         */
        CONNECTED,
        /**
         * Network must be connected and unmetered.
         */
        UNMETERED
    }
}
