package com.oplus.ums.groupcard.defaultservice.bean;

import java.util.List;

/** Offline stand-in for the ROM class. */
public class DefaultServiceEntity {
    public DefaultServiceEntity(int index, String name, List<Object> compose,
                                List<Object> optional, int checkedPosition, boolean shouldFilter) {
    }

    public List<Object> getComposeServices() {
        return null;
    }

    public List<Object> getOptionalServices() {
        return null;
    }

    public String getComposeName() {
        return "";
    }

    public int getIndex() {
        return 0;
    }

    public boolean getShouldFilter() {
        return false;
    }

    public int getCheckedPosition() {
        return 0;
    }
}
