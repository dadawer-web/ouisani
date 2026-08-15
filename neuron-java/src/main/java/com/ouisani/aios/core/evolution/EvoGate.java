package com.ouisani.aios.core.evolution;

/** Ordered gates an evolution asset must pass before it may affect a later split. */
public enum EvoGate {
    TARGETED_REGRESSION,
    GLOBAL_REGRESSION,
    STACK_CONFIRMATION,
    SHADOW,
    CANARY
}
