package io.github.carloslimajlle.liquimanager.cli;

/** The version stamped in the jar manifest; "dev" when running from classes. */
final class Version {

    private Version() {
    }

    static String current() {
        String version = Version.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
