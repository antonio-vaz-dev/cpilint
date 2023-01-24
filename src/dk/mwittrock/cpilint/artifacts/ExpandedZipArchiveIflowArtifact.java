package dk.mwittrock.cpilint.artifacts;

import dk.mwittrock.cpilint.IflowXml;
import dk.mwittrock.cpilint.util.ExtensionPredicate;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;

import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ExpandedZipArchiveIflowArtifact implements IflowArtifact {

    private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";
    private static final String EXT_PARAMS_REPLACE_XSLT_PATH = "resources/xslt/ReplaceExternalParameters.xsl";
    private static final String NAME_MANIFEST_HEADER = "Bundle-Name";
    private static final String ID_MANIFEST_HEADER = "Bundle-SymbolicName";
    private static final String IFLOW_RESOURCES_BASE_PATH = "src/main/resources/";
    private static final String EXT_PARAMS_PATH = IFLOW_RESOURCES_BASE_PATH + "parameters.prop";


    private final IflowArtifactTag tag;
    private final IflowXml iflowXml;
    private final Map<ArtifactResourceType, Collection<ArtifactResource>> resources;

    private ExpandedZipArchiveIflowArtifact(IflowArtifactTag tag, Map<ArtifactResourceType, Collection<ArtifactResource>> resources, IflowXml iflowXml) {
        // Private, since instances are returned by the static factory methods.
        this.tag = tag;
        this.resources = resources;
        this.iflowXml = iflowXml;
    }

    @Override
    public Collection<ArtifactResource> getResourcesByType(ArtifactResourceType type) {
        /*
         * The resources map is expected to contain a key for every artifact
         * resource type. If this iflow artifact does not contain any resources of
         * the specified type, the resources map will contain an empty collection
         * for that key.
         */
        if (!resources.containsKey(type)) {
            throw new IflowArtifactError("Artifact resource type not found");
        }
        return Collections.unmodifiableCollection(resources.get(type));
    }

    @Override
    public IflowXml getIflowXml() {
        return iflowXml;
    }

    @Override
    public IflowArtifactTag getTag() {
        return tag;
    }

    public static IflowArtifact from(Path p) throws IOException, SaxonApiException {

        Path iflowPath = Path.of(p.toString(), IFLOW_RESOURCES_BASE_PATH + "scenarioflows/integrationflow/");


        // Extract the iflow's name and ID from the manifest.
        IflowArtifactTag tag = createTag(Files.newInputStream(Path.of(p.toString(), MANIFEST_PATH)).readAllBytes());
        if (tag == null) {
            throw new IflowArtifactError("This path is probably not an Iflow directory, Manifest not found!");
        }

        // Get an IflowXml object.
        List<File> iflowXmlPaths = Arrays.asList(Objects.requireNonNull(new File(iflowPath.toUri()).listFiles()))
                .stream()
                .filter(new ExtensionPredicate(".iflw"))
                .collect(Collectors.toList());

        if (iflowXmlPaths.size() != 1) {
            throw new IflowArtifactError("Unable to locate iflow XML in artifact");
        }

        byte[] iFlowWithExternalParameters = null;
        if (Files.exists(Path.of(p.toString(), EXT_PARAMS_PATH))) {
            iFlowWithExternalParameters = replaceExternalParameters(p, iflowXmlPaths.get(0).getAbsolutePath());
        }


        // Create ArtifactResource objects for all resources.
        Map<ArtifactResourceType, Collection<ArtifactResource>> resourcesMap = new HashMap<>();
        Path scriptPath = Path.of(p.toString(), IFLOW_RESOURCES_BASE_PATH + "script/");
        resourcesMap.put(ArtifactResourceType.GROOVY_SCRIPT, getArtifactResourceCollection(
                scriptPath,
                tag, new ExtensionPredicate(".groovy").or(new ExtensionPredicate("gsh")),
                ArtifactResourceType.GROOVY_SCRIPT));

        resourcesMap.put(ArtifactResourceType.JAVASCRIPT_SCRIPT, getArtifactResourceCollection(
                scriptPath,
                tag, new ExtensionPredicate(".js"),
                ArtifactResourceType.JAVASCRIPT_SCRIPT));


        resourcesMap.put(ArtifactResourceType.XSD, getArtifactResourceCollection(
                Paths.get(p.toString(), IFLOW_RESOURCES_BASE_PATH + "xsd/"),
                tag, new ExtensionPredicate(".xsd"),
                ArtifactResourceType.XSD));

        Path mappingPath = Path.of(p.toString(), IFLOW_RESOURCES_BASE_PATH + "mapping/");
        resourcesMap.put(ArtifactResourceType.MESSAGE_MAPPING, getArtifactResourceCollection(
                mappingPath,
                tag, new ExtensionPredicate(".mmap"),
                ArtifactResourceType.MESSAGE_MAPPING));

        resourcesMap.put(ArtifactResourceType.XSLT_MAPPING, getArtifactResourceCollection(
                mappingPath,
                tag, new ExtensionPredicate(".xsl").or(new ExtensionPredicate(".xslt")),
                ArtifactResourceType.XSLT_MAPPING));


        resourcesMap.put(ArtifactResourceType.IFLOW, getArtifactResourceCollection(
                iflowPath,
                tag, new ExtensionPredicate(".iflw"),
                ArtifactResourceType.IFLOW, iFlowWithExternalParameters));

        resourcesMap.put(ArtifactResourceType.JAVA_ARCHIVE, getArtifactResourceCollection(
                Paths.get(p.toString(), IFLOW_RESOURCES_BASE_PATH + "lib/"),
                tag, new ExtensionPredicate(".jar").or(new ExtensionPredicate(".zip")),
                ArtifactResourceType.JAVA_ARCHIVE));

        resourcesMap.put(ArtifactResourceType.WSDL, getArtifactResourceCollection(
                Paths.get(p.toString(), IFLOW_RESOURCES_BASE_PATH + "wsdl/"),
                tag, new ExtensionPredicate(".wsdl"),
                ArtifactResourceType.WSDL));

        resourcesMap.put(ArtifactResourceType.EDMX, getArtifactResourceCollection(
                Paths.get(p.toString(), IFLOW_RESOURCES_BASE_PATH + "edmx/"),
                tag, new ExtensionPredicate(".edmx"),
                ArtifactResourceType.EDMX));

        resourcesMap.put(ArtifactResourceType.OPERATION_MAPPING, getArtifactResourceCollection(
                mappingPath,
                tag, new ExtensionPredicate(".opmap"),
                ArtifactResourceType.OPERATION_MAPPING));


        Path iflowXmlPath = Path.of(iflowXmlPaths.get(0).getAbsolutePath());
        IflowXml iflowXml = IflowXml.fromInputStream(Files.newInputStream(iflowXmlPath));

        return new ExpandedZipArchiveIflowArtifact(tag, resourcesMap, iflowXml);
    }

    private static Collection<ArtifactResource> getArtifactResourceCollection(Path p, IflowArtifactTag tag, Predicate<File> ext, ArtifactResourceType type, byte[] content) throws IOException {

        File[] files = new File(p.toUri()).listFiles();
        List<File> gsh = new ArrayList<>();
        if(files != null){
         gsh = Arrays.asList(files).stream().filter(ext)
                .collect(Collectors.toList());
        }

        Collection<ArtifactResource> artifactResources = new ArrayList<>();
        for (File f : gsh) {
            artifactResources.add(new ArtifactResource(tag, type,
                    resourceNameFromResourcePath(f.getAbsolutePath()),
                    content == null?Files.newInputStream(Path.of(f.getAbsolutePath())).readAllBytes() : content));
        }
        return artifactResources;
    }
    private static Collection<ArtifactResource> getArtifactResourceCollection(Path p, IflowArtifactTag tag, Predicate<File> ext, ArtifactResourceType type) throws IOException {
        return getArtifactResourceCollection(p, tag, ext, type, null);
    }


    private static byte[] replaceExternalParameters(Path root, String iflowXmlPath) throws IOException, SaxonApiException {
        InputStream iflowXml = new ByteArrayInputStream(Files.newInputStream(Path.of(iflowXmlPath)).readAllBytes());
        InputStream stylesheet = ExpandedZipArchiveIflowArtifact.class.getClassLoader().getResourceAsStream(EXT_PARAMS_REPLACE_XSLT_PATH);
        Map<String, String> parametersMap = new HashMap<>();
        Properties props = new Properties();
        props.load(new ByteArrayInputStream(Files.newInputStream(Path.of(root.toString(), EXT_PARAMS_PATH)).readAllBytes()));
        props.forEach((k, v) -> parametersMap.put((String) k, (String) v));
        return transformIflowXml(stylesheet, iflowXml, Collections.unmodifiableMap(parametersMap));
    }



    private static byte[] transformIflowXml(InputStream stylesheet, InputStream iflowXml, Map<String, String> parametersMap) throws SaxonApiException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Processor proc = new Processor(false);
        XsltCompiler comp = proc.newXsltCompiler();
        XsltExecutable exe = comp.compile(new StreamSource(stylesheet));
        XsltTransformer xslt = exe.load();
        xslt.setSource(new StreamSource(iflowXml));
        xslt.setDestination(proc.newSerializer(out));
        XdmMap xdmMap = XdmMap.makeMap(parametersMap);
        xslt.setParameter(new QName("parameterMap"), xdmMap);
        xslt.transform();
        return out.toByteArray();
    }

    private static String extractId(String manifestValue) {
        /*
         *  Here are the two known formats of the iflow ID manifest value:
         *
         *  HCITracker
         *  HCITracker; singleton:=true
         *
         *  If our assumptions about the value retrieved from the manifest
         *  do not hold, an IflowArtifactError is thrown.
         *
         *  TODO: Confirm the format of the manifest value.
         */
        if (manifestValue == null || manifestValue.length() == 0) {
            throw new IflowArtifactError("Empty manifest value when trying to extract iflow ID");
        }
        String[] tokens = manifestValue.split(";");
        if (tokens.length < 1 || tokens.length > 2) {
            throw new IflowArtifactError("Unexpected manifest value format when trying to extract iflow ID");
        }
        return tokens[0];
    }

    private static IflowArtifactTag createTag(byte[] manifestContents) throws IOException {
        Manifest m = new Manifest(new ByteArrayInputStream(manifestContents));
        Attributes a = m.getMainAttributes();
        if (!a.containsKey(new Attributes.Name(NAME_MANIFEST_HEADER))) {
            throw new IflowArtifactError("Iflow manifest does not contain the expected name header");
        }
        if (!a.containsKey(new Attributes.Name(ID_MANIFEST_HEADER))) {
            throw new IflowArtifactError("Iflow manifest does not contain the expected ID header");
        }
        String id = extractId(a.getValue(ID_MANIFEST_HEADER));
        String name = a.getValue(NAME_MANIFEST_HEADER);
        return new IflowArtifactTag(id, name);
    }


    private static String resourceNameFromResourcePath(String resourcePath) {

        // The resource name is everything following the last slash.
        int lastSlashIndex = resourcePath.lastIndexOf('/');
        // The path cannot end in a slash.
        if (lastSlashIndex == resourcePath.length() - 1) {
            throw new IllegalArgumentException("A resource path cannot end in a slash");
        }
        return resourcePath.substring(lastSlashIndex + 1);
    }

}
