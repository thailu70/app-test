package live.passo.smsgateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives Android's per-part SMSC submission result for encrypted Passo jobs. */
public final class SmsSentReceiver extends BroadcastReceiver {
    static final String EXTRA_JOB_ID = "job_id";
    static final String EXTRA_LEASE = "lease";
    static final String EXTRA_PART_INDEX = "part_index";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String jobId = intent.getStringExtra(EXTRA_JOB_ID);
        String lease = intent.getStringExtra(EXTRA_LEASE);
        int partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1);
        if (jobId == null || lease == null) return;
        SmsGatewayService.recordSmsPartResult(jobId, lease, partIndex, getResultCode());
    }
}
