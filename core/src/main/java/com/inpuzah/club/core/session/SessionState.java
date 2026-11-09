package com.inpuzah.club.core.session;

public enum SessionState {
    /** Profile request is in flight. */
    LOADING,
    /** Profile loaded and available. */
    READY,
    /** Loading failed or timed out; profile features are unavailable for this session. */
    UNAVAILABLE,
    /** Player disconnected; the session no longer accepts updates. */
    CLOSED
}
