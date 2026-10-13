/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapSourceTest {
    @TempDir Path directory;
    @Test void fingerprintDependsOnParsedBytesAndPortableRelativePathInsteadOfInstallLocation() throws Exception {
        byte[] bytes={1,2,3};var root=directory.resolve("game");var other=directory.resolve("other");
        var first=root.resolve("Data/Editor/Maps/Level1.kwd");var second=other.resolve("data/editor/maps/level1.kwd");
        Files.createDirectories(first.getParent());Files.createDirectories(second.getParent());Files.write(first,bytes);Files.write(second,bytes);
        var original=MapSource.fingerprint(root,first,bytes);
        var relocated=MapSource.fingerprint(other,second,bytes);
        assertEquals(original,relocated);bytes[0]=4;
        assertNotEquals(original.sha256(),MapSource.fingerprint(root,first,bytes).sha256());
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",original.sha256());
    }
    @Test void linkedDataRootMatchesRealRootAndExternalTargetsRemainRejected() throws Exception {
        var real=directory.resolve("real");Files.createDirectories(real);
        var file=real.resolve("level.kwd");Files.write(file,new byte[]{1});
        var linked=directory.resolve("linked");Files.createSymbolicLink(linked,real);
        assertEquals(MapSource.fingerprint(real,file,new byte[]{1}),MapSource.fingerprint(linked,file.toRealPath(),new byte[]{1}));
        assertEquals(MapSource.fingerprint(real,file,new byte[]{1}),MapSource.fingerprint(linked,linked.resolve("level.kwd"),new byte[]{1}));
        var outside=directory.resolve("outside.kwd");Files.write(outside,new byte[]{1});
        Files.createSymbolicLink(real.resolve("external.kwd"),outside);
        assertThrows(IllegalArgumentException.class,()->MapSource.fingerprint(linked,real.resolve("external.kwd"),new byte[]{1}));
    }
    @Test void installedManifestResolvesAcrossRelocationCaseAndLinkedRoot() throws Exception {
        var original = directory.resolve("original");var installed = directory.resolve("installed");
        var map = original.resolve("Data/Maps/Level1.kwd");var data = original.resolve("Data/Creatures.kld");
        Files.createDirectories(map.getParent());Files.write(map,new byte[]{1,2});Files.write(data,new byte[]{3});
        var source = new MapSource("data/maps/level1.kwd",List.of(MapSource.fingerprint(original,map,new byte[]{1,2}),MapSource.fingerprint(original,data,new byte[]{3})));
        var relocated = installed.resolve("DATA/MAPS/LEVEL1.KWD");
        Files.createDirectories(relocated.getParent());Files.write(relocated,new byte[]{1,2});Files.write(installed.resolve("DATA/CREATURES.KLD"),new byte[]{3});
        var linked = directory.resolve("linked-install");Files.createSymbolicLink(linked,installed);
        assertEquals(relocated.toRealPath(),source.resolve(linked));
        assertEquals(relocated.toRealPath(),source.resolve(installed));
    }
    @Test void missingChangedOrResizedDependencyRejectsBeforeMapBinding() throws Exception {
        var map = directory.resolve("level.kwd");var data = directory.resolve("creatures.kld");
        Files.write(map,new byte[]{1});Files.write(data,new byte[]{2});
        var source = new MapSource("level.kwd",List.of(MapSource.fingerprint(directory,map,new byte[]{1}),MapSource.fingerprint(directory,data,new byte[]{2})));
        Files.write(data,new byte[]{3});assertThrows(java.io.IOException.class,()->source.resolve(directory));
        Files.write(data,new byte[]{2,3});assertThrows(java.io.IOException.class,()->source.resolve(directory));
        Files.delete(data);assertThrows(java.io.IOException.class,()->source.resolve(directory));
        Files.write(data,new byte[]{2});assertEquals(map.toRealPath(),source.resolve(directory));
    }
    @Test void externalSymlinkWithMatchingBytesIsNotAnInstalledMap() throws Exception {
        var root = directory.resolve("root");Files.createDirectories(root);
        var outside = directory.resolve("outside.kwd");Files.write(outside,new byte[]{1});
        Files.createSymbolicLink(root.resolve("level.kwd"),outside);
        var source = new MapSource("level.kwd",List.of(new MapSource.File("level.kwd",1,MapSource.digest(new byte[]{1}))));
        assertThrows(java.io.IOException.class,()->source.resolve(root));
    }
    @Test void malformedLocatorsDigestsAndHeaderBindingAreRejected() {
        for(String path:List.of("", "/absolute", "../outside", "data/../outside", "data//map", "data/./map", "C:/map", "data/\u0000map"))
            assertThrows(IllegalArgumentException.class,()->new MapSource.File(path,0,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new MapSource.File("map",-1,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new MapSource.File("map",3,"bad"));
        assertThrows(IllegalArgumentException.class,()->new MapSource("map",List.of()));
        assertThrows(IllegalArgumentException.class,()->new MapSource("map",List.of(new MapSource.File("other",3,"a".repeat(64)))));
    }
}
