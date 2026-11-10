package com.inpuzah.club.core.social.model;

/** Viewer-relative state; never discloses the other player's private block list. */
public enum Relationship {
    NONE, FRIENDS, INCOMING_REQUEST, OUTGOING_REQUEST, BLOCKED
}
