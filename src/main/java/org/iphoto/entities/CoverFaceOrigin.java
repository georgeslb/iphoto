package org.iphoto.entities;

/**
 * Defines the origin of how a person's cover face was selected.
 */
public enum CoverFaceOrigin {
    /**
     * The cover face was selected automatically by the system.
     * (e.g., the largest, most centered, or highest quality face).
     */
    CALCULATED,

    /**
     * The cover face was explicitly chosen by the user.
     */
    MANUAL
}