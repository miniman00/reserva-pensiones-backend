package uy.pensiones.enums;

/**
 * Perfil de residentes al que está orientada la pensión.
 * OPEN_TO_ALL no impone restricción; STUDENTS_PREFERRED expresa una preferencia
 * informativa; STUDENTS_ONLY requiere que la persona sea estudiante.
 */
public enum ResidentProfile {
    OPEN_TO_ALL,
    STUDENTS_PREFERRED,
    STUDENTS_ONLY
}
