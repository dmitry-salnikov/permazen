
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package io.permazen.core.type;

import io.permazen.util.ByteReader;

import java.net.Inet6Address;

/**
 * Non-null {@link Inet6Address} type. Null values are not supported by this class.
 */
public class Inet6AddressType extends AbstractInetAddressType<Inet6Address> {

    public static final int LENGTH = 16;

    static final String PATTERN = "[:\\p{XDigit}]+";

    private static final long serialVersionUID = -5443623479173176261L;

    public Inet6AddressType() {
        super(Inet6Address.class, PATTERN);
    }

    @Override
    protected int getLength(ByteReader reader) {
        return LENGTH;
    }
}

