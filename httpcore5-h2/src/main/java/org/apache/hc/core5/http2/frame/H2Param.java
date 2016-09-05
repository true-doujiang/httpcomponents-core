/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.core5.http2.frame;

public enum H2Param {

    HEADER_TABLE_SIZE      (0x1,   4096),
    ENABLE_PUSH            (0x2,   1),
    MAX_CONCURRENT_STREAMS (0x3,   Integer.MAX_VALUE),
    INITIAL_WINDOW_SIZE    (0x4,   65535),
    MAX_FRAME_SIZE         (0x5,   16384),
    MAX_HEADER_LIST_SIZE   (0x6,   Integer.MAX_VALUE);

    int code;

    int initialValue;

    H2Param(final int code, final int initialValue) {
        this.code = code;
        this.initialValue = initialValue;
    }

    public int getCode() {
        return code;
    }

    public int getInitialValue() {
        return initialValue;
    }

    private static final H2Param[] LOOKUP_TABLE = new H2Param[6];
    static {
        for (H2Param param: H2Param.values()) {
            LOOKUP_TABLE[param.code - 1] = param;
        }
    }

    public static H2Param valueOf(final int code) {
        if (code < 1 || code > LOOKUP_TABLE.length) {
            return null;
        }
        return LOOKUP_TABLE[code - 1];
    }

    public static String toString(final int code) {
        if (code < 1 || code > LOOKUP_TABLE.length) {
            return Integer.toString(code);
        }
        return LOOKUP_TABLE[code - 1].name();
    }

};