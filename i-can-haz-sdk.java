///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 18
//DEPS info.picocli:picocli:4.6.3
//DEPS info.picocli:picocli-codegen:4.6.3
//DEPS hu.webarticum:tree-printer:2.0.0
//DEPS io.vavr:vavr:0.10.4
//DEPS com.vdurmont:emoji-java:5.1.1

import hu.webarticum.treeprinter.SimpleTreeNode;
import hu.webarticum.treeprinter.TreeNode;
import hu.webarticum.treeprinter.printer.listing.ListingTreePrinter;
import io.vavr.collection.Seq;
import io.vavr.control.Validation;
import io.vavr.control.Validation.Invalid;
import io.vavr.control.Validation.Valid;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.vavr.Function2.constant;
import static io.vavr.control.Validation.combine;
import static io.vavr.control.Validation.invalid;
import static io.vavr.control.Validation.valid;
import static java.lang.System.out;
import static java.util.stream.Collectors.flatMapping;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.generate;
import static picocli.CommandLine.Help.*;

import com.vdurmont.emoji.EmojiParser;

@Command(name = "i-can-haz-sdk", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "Checks if a ZIP archive is suitable for publication via SDKMAN!.")
class ICanHazSdk implements Callable<Integer> {
    @Parameters(index = "0", description = "The URL where the archive is located.")
    private URL url;

    public static void main(String... args) {
        System.exit(new CommandLine(new ICanHazSdk()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        var rootDirectories = rootDirectories(url);
        var directoryTree =
            rootDirectories
                .entrySet()
                .stream()
                .map(ICanHazSdk::directoryTree)
                .collect(buildTree(highlight(lastPathSegment(url.getPath()))));

        new ListingTreePrinter().print(directoryTree);

        out.println();

        var result =
            combine(hasSingleTopLevelDirectory(rootDirectories), hasSecondLevelBinDirectory(rootDirectories))
                .fold()
                .ap((hasSingleTopLevelDirectory, hasSecondLevelBinDirectory) -> List.of(hasSingleTopLevelDirectory, hasSecondLevelBinDirectory));

        if (result.isInvalid()) {
            result.getError().map(ICanHazSdk::failure).forEach(out::println);

            return 1;
        }

        result.get().stream().map(ICanHazSdk::success).forEach(out::println);

        out.println();
        out.println(EmojiParser.parseToUnicode(":tada: Your archive is suitable for publication via SDKMAN!"));

        return 0;
    }

    private static String failure(String text) {
        return EmojiParser.parseToUnicode(":x: %s").formatted(text);
    }

    private static String success(String text) {
        return EmojiParser.parseToUnicode(":white_check_mark: %s").formatted(text);
    }

    private static String highlight(String text) {
        return Ansi.AUTO.string("@|bold,underline %s|@").formatted(text);
    }

    private static Validation<String, String> hasSingleTopLevelDirectory(Map<String, List<String>> directoryTree) {
        return
                directoryTree.size() == 1
                        ? valid("The archive contains a top-level directory.")
                        : invalid("SKDMAN! requires a single, top-level directory.");
    }

    private static Validation<String, String> hasSecondLevelBinDirectory(Map<String, List<String>> directoryTree) {
        return
                directoryTree.size() == 1 && hasBinDirectory(directoryTree.values())
                        ? valid("The archive contains a bin/ directory.")
                        : invalid("SDKMAN! requires a bin/ directory directly under the root directory.");
    }

    private static boolean hasBinDirectory(Collection<List<String>> directoryTree) {
        return directoryTree.stream().anyMatch(directory -> directory.contains("bin/"));
    }

    private static String lastPathSegment(String path) {
        return path.replaceFirst("^.+/", "");
    }

    private static TreeNode directoryTree(Map.Entry<String, List<String>> directory) {
        return
                directory
                        .getValue()
                        .stream()
                        .map(SimpleTreeNode::new)
                        .collect(buildTree(directory.getKey()));
    }

    private static Collector<TreeNode, SimpleTreeNode, SimpleTreeNode> buildTree(String content) {
        return Collector.of(
                () -> new SimpleTreeNode(content),
                SimpleTreeNode::addChild,
                (left, right) -> {
                    right.children().forEach(left::addChild);

                    return left;
                }
        );
    }

    private static Map<String, List<String>> rootDirectories(URL url) throws IOException {
        try (var inputStream = new ZipInputStream(newUrlConnectionInputStream(url))) {
            return
                    generate(wrap(inputStream::getNextEntry))
                            .takeWhile(Objects::nonNull)
                            .map(ZipEntry::getName)
                            .filter(toPredicate(ICanHazSdk::isDirectory).and(ICanHazSdk::isAtMostSecondLevel))
                            .collect(groupingBy(ICanHazSdk::rootDirectory, flatMapping(ICanHazSdk::childDirectory, toList())));
        }
    }

    private static <T> Predicate<T> toPredicate(Predicate<T> p) {
        return p;
    }

    private static boolean isDirectory(String filename) {
        return filename.endsWith("/");
    }

    private static boolean isAtMostSecondLevel(String filename) {
        return filename.chars().filter(c -> c == '/').count() <= 2;
    }

    private static boolean isRootDirectory(String filename) {
        return filename.matches("^[^/]+/$");
    }

    private static String rootDirectory(String filename) {
        return filename.replaceFirst("(?<=/)[^/]+/$", "");
    }

    private static Stream<String> childDirectory(String filename) {
        return isRootDirectory(filename) ? Stream.of() : Stream.of(filename.replaceFirst("^[^/]+/", ""));
    }

    private static InputStream newUrlConnectionInputStream(URL url) throws IOException {
        var connection = url.openConnection();
        connection.connect();

        return connection.getInputStream();
    }

    private static <T> Supplier<T> wrap(ThrowingSupplier<T> supplier) {
        return () -> {
            try {
                return supplier.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
