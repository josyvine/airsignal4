package com.example.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class SimManager {

    private static final String TAG = "SimManager";

    public static class SimInfo {
        private final int subId;
        private final int simSlotIndex;
        private final String displayName;
        private final String carrierName;

        public SimInfo(int subId, int simSlotIndex, String displayName, String carrierName) {
            this.subId = subId;
            this.simSlotIndex = simSlotIndex;
            this.displayName = displayName;
            this.carrierName = carrierName;
        }

        public int getSubId() {
            return subId;
        }

        public int getSimSlotIndex() {
            return simSlotIndex;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getCarrierName() {
            return carrierName;
        }

        @Override
        public String toString() {
            if (displayName != null && !displayName.isEmpty()) {
                return displayName;
            }
            return "SIM " + (simSlotIndex + 1);
        }
    }

    /**
     * Retrieves all active SIM cards on the device.
     */
    public static List<SimInfo> getActiveSims(Context context) {
        List<SimInfo> simList = new ArrayList<>();

        if (context == null) {
            simList.add(new SimInfo(-1, 0, "Default SIM", "Default"));
            return simList;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                    if (subManager != null) {
                        List<SubscriptionInfo> activeSubList = subManager.getActiveSubscriptionInfoList();
                        if (activeSubList != null && !activeSubList.isEmpty()) {
                            for (SubscriptionInfo subInfo : activeSubList) {
                                int subId = subInfo.getSubscriptionId();
                                int slotIndex = subInfo.getSimSlotIndex();
                                CharSequence carrier = subInfo.getCarrierName();
                                CharSequence display = subInfo.getDisplayName();

                                String carrierStr = (carrier != null) ? carrier.toString() : "Carrier";
                                String displayStr = (display != null) ? display.toString() : "SIM " + (slotIndex + 1);

                                String label = "SIM " + (slotIndex + 1) + " (" + carrierStr + ")";
                                simList.add(new SimInfo(subId, slotIndex, label, carrierStr));
                            }
                        }
                    }
                } catch (Exception e) {
                    AirLogger.e(TAG, "Failed to query SubscriptionManager for SIMs", e);
                }
            } else {
                AirLogger.i(TAG, "READ_PHONE_STATE permission not granted for SIM querying");
            }
        }

        if (simList.isEmpty()) {
            simList.add(new SimInfo(-1, 0, "SIM 1", "Default"));
        }

        return simList;
    }
}