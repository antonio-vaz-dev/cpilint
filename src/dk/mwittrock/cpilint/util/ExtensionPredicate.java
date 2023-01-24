package dk.mwittrock.cpilint.util;

import java.io.File;
import java.util.function.Predicate;

public class ExtensionPredicate implements Predicate<File> {

    String extension;

    public ExtensionPredicate(String extension) {
        this.extension = extension;
    }

    @Override
    public boolean test(File file) {
        return file != null && file.getName().endsWith(extension);
    }}
