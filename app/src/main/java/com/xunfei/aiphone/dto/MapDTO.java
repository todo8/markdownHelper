package com.xunfei.aiphone.dto;

import java.util.HashMap;
import java.util.Map;

public class MapDTO {

    public static Map<String, Object> to(Object ...objs) {
        if (null == objs || 1 > objs.length) {
            return new HashMap<String, Object>();
        }

        if (1 == (objs.length % 2)) {
            throw new RuntimeException();
        }

        Map<String, Object> bean = new HashMap<String, Object>();
        for (int i = 0, size = objs.length; i < size; i += 2) {
            Object key = objs[i];
            if (!(key instanceof String)) {
                throw new RuntimeException("key is not string");
            }
            bean.put((String) key, objs[i + 1]);
        }
        return bean;
    }

}
