# Keep private file and lock references until process exit to prevent early garbage collection.
-keep class net.conw.unit.privacy.PrivateSession {
    <fields>;
}
