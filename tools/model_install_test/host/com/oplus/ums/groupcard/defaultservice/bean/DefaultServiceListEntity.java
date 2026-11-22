package com.oplus.ums.groupcard.defaultservice.bean;

import java.util.List;

/** Offline stand-in for the ROM class; only the members DefaultServiceHook reflects on. */
public class DefaultServiceListEntity {
    private List<Object> defaultServiceList;

    public List<Object> getDefaultServiceList() {
        return defaultServiceList;
    }

    public void updateCheckedPosition(int tabIndex, int position) {
        if (tabIndex >= 0 && defaultServiceList != null && tabIndex < defaultServiceList.size()) {
            // no-op: offline test only needs the signature
        }
    }
}
