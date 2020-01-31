/*
 *  This file is part of OSMT.
 *  
 *  OSMT is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 *
 *  OSMT is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.    See the
 *  GNU General Public License for more details.
 *
 *  Based on cutTheOsmPlanet:
 *      Copyright (C) 2010 Heiko Budras
 *      Author: Heiko Budras
 *      Tile logic: Carsten Schwede
 *  Modified by: Jan Behrens - 2011
 */

package osmt;

import java.io.*;
import java.util.*;

public class Split {
    String inputFileName;
    NodeToTileNumber n2tn;
    BufferedReader br;
    String dataDir;
    float tilesize;
    boolean slim;
    
    float nodeLat, nodeLon;
    long nodeId = 0, ref = 0, firstRef = 0, previousRef = 0;
    long tn = 0, previousTn = 0;
    String line, wayLine = "";
    String target = "";
    Tile t, previousT;
    
    HashMap<String, String> attr;
    
    HashMap<Long, Tile> tilesMap = new HashMap<Long, Tile>();
    
    //sets and maps used during way processing
    HashSet<Tile> tiles = new HashSet<Tile>();  //tilesWayIsIn
    HashMap<Tile, Boolean> refsHaveBeenWritten = new HashMap<Tile, Boolean>();
    HashMap<Tile, Long> lastRemoteNodeAdded = new HashMap<Tile, Long>();
    HashMap<Tile, ArrayList<Long>> refs = new HashMap<Tile, ArrayList<Long>>();
    HashMap<Tile, ArrayList<Long>> refTn = new HashMap<Tile, ArrayList<Long>>();

    // for relation processing
    class RelationMember {
        String role = "";
        Long ref;
        String type;

        public RelationMember(String type, Long ref, String role) {
            this.role = role;
            this.ref = ref;
            this.type = type;
        }

        public String toString() {
            return "<member type=\"" + this.type + "\" ref=\"" + this.ref + "\" role=\"" + this.role + "\"/>";
        }
    }

    long wayId = 0; // way id of the currently processed way
    HashMap<Long, ArrayList<Long>> wayNodes = new HashMap<Long, ArrayList<Long>>(); // nodes that are part of the ways
    boolean skipRelation = false; // a flag for skipping relations containing other relations, as these are too general
    String relationLine = "";
    HashMap<Tile, ArrayList<RelationMember>> members = new HashMap<Tile, ArrayList<RelationMember>>();
    
    /**
     * Constructor
     * @param inputFileName
     * @param node2tnFile
     * @param dataDir
     * @param tilesize
     * @param slim
     */
    public Split(String inputFileName, String node2tnFile, String dataDir, float tilesize, boolean slim) {
        try {
            n2tn = new NodeToTileNumber(node2tnFile, tilesize);
        } catch (Exception e) {
            System.err.println("Error writing index file");
            System.exit(1);
        }
        
        this.inputFileName = inputFileName;
        this.dataDir = dataDir;
        this.tilesize = tilesize;
        this.slim = slim;
        
        try {
            FileReader fr = new FileReader(inputFileName);
            br = new BufferedReader(fr);
            
            boolean invalidOSM = true;
            
            for (int i = 0; i < 3; i++) {
                if (!br.readLine().contains("<osm")) {
                    invalidOSM = false;
                }
            }
            if (invalidOSM) {
                System.err.println("Error: no OSM XML root tag found");
                System.exit(1);
            }
            fr = new FileReader(inputFileName);
            br = new BufferedReader(fr);
        } catch (Exception e) {
            System.err.println("Error opening input file: " + inputFileName);
            System.exit(1);
        }
    }
    
    /**
     * split
     * @throws Exception
     */
    public void split() throws Exception {
        System.out.println("Splitting file " + inputFileName + ", tile size: " + tilesize + "°");

        Date startDate = new Date();
        long startTime = startDate.getTime(), timeRunning, lineCount = 0;
        final long lineThreshold = 100000;
        
        boolean debug = false;
        
        //read lines
        while ((line = br.readLine()) != null) {
            lineCount++;
            
            //begin node
            if (line.contains("<node ")) {
                target = "nodes";
                
                //parse id, lat, lon
                attr = parseAttr(line);
                nodeId = Long.valueOf(attr.get("id"));
                nodeLat = Float.valueOf(attr.get("lat"));
                nodeLon = Float.valueOf(attr.get("lon"));
                
                //write tile number to random access file
                tn = n2tn.setTn(nodeId, nodeLat, nodeLon);
                
                //remember tile
                if (!tilesMap.containsKey(tn)) {
                    t = new Tile(tn, dataDir);
                    tilesMap.put(tn, t);
                }
                else {
                    t = tilesMap.get(tn);
                }
                
                //write
                if (slim) {
                    t.nodes.add(nodeId);
                    t.writeTmpNodes(line);
                }
                else {
                    storeNode(nodeId, t, line);
                }
            }
            //end node
            else if (line.contains("</node")) {
                //write
                if (slim) {
                    t.writeTmpNodes(line);
                }
                else {
                    storeNode(nodeId, t, line);
                }
            }
            //begin way
            else if (line.contains("<way ")) {
                //init
                target = "ways";
                wayLine = line;     //save the <way> line, will be written later
                attr = parseAttr(line);
                wayId = Long.valueOf(attr.get("id"));
                firstRef = 0;
                previousRef = 0;
                previousTn = 0;
                tiles.clear();
                refsHaveBeenWritten.clear();
                lastRemoteNodeAdded.clear();
                refs.clear();
                refTn.clear();
                
                //debug = wayLine.contains("way id=\"0\""); //insert way ID to debug
            }
            //nd
            else if (line.contains("<nd ")) {
                //parse ref
                attr = parseAttr(line);
                ref = Long.valueOf(attr.get("ref"));

                ArrayList<Long> nodes = wayNodes.getOrDefault(wayId, new ArrayList<Long>());
                nodes.add(ref);
                wayNodes.put(wayId, nodes);
                
                //get tile
                tn = n2tn.getTn(ref);
                t = tilesMap.get(tn);
                tiles.add(t);
                
                //debug
                if (debug) {
                    System.out.println("== ref "+ref+" in tile "+tn+" ==");
                }
                
                //init
                if (!refs.containsKey(t)) {
                    refs.put(t, new ArrayList<Long>());
                    refTn.put(t, new ArrayList<Long>());
                    lastRemoteNodeAdded.put(t, 0L);
                }
                
                //if way crosses a tile boundary...
                if (previousTn != tn && previousTn != 0) {
                    //debug
                    if (debug) {
                        System.out.println("copying node "+ref+" from "+tn+" to "+previousTn);
                    }
                    
                    //copy <node> backward
                    if (slim) {
                        previousT.nodes.add(ref);
                        previousT.nodesExtra.add(ref);
                        previousT.writeRemoteNode(ref, t);
                    }
                    else {
                        storeRemoteNode(ref, t, previousT);
                    }
                    
                    //copy ref backward
                    refs.get(previousT).add(ref);
                    refTn.get(previousT).add(tn);
                    
                    lastRemoteNodeAdded.put(previousT, ref);
                    
                    if (lastRemoteNodeAdded.get(t) != previousRef) {    //prevent nodes from being inserting two subsequent times
                        //debug
                        if (debug) {
                            System.out.println("copying node "+previousRef+" from "+previousTn+" to "+tn);
                        }
                        
                        //copy <node> forward
                        if (slim) {
                            t.nodes.add(previousRef);
                            t.nodesExtra.add(previousRef);
                            t.writeRemoteNode(previousRef, previousT);
                        }
                        else {
                            storeRemoteNode(previousRef, previousT, t);
                        }
                        
                        //copy ref forward
                        refs.get(t).add(previousRef);
                        refTn.get(t).add(previousTn);
                    }
                }
                
                //other nd
                refs.get(t).add(ref);
                refTn.get(t).add(0L);
                
                if (firstRef == 0) {
                    firstRef = ref;
                }
                                
                previousT = t;
                previousTn = tn;
                previousRef = ref;
            }
            //end way
            else if (line.contains("</way")) {
                //write all lines if not yet done (in each tile)
                for (Tile i : tiles) {
                    if (!refsHaveBeenWritten.containsKey(i) || !refsHaveBeenWritten.get(i)) {
                        //Closed ways: If the last nd equals the first nd, append to all segments
                        //the first (local) nd.
                        if (ref == firstRef && ref != refs.get(i).get(refs.get(i).size() - 1)) {
                            refs.get(i).add(refs.get(i).get(0));
                            refTn.get(i).add(refTn.get(i).get(0));
                        }
                        //debug
                        if (debug) {
                            System.out.println("closed way, write first nd again in tile "+tn);
                        }
                                                
                        //write <way>, <nd>s
                        i.writeTmpWays(wayLine);
                        writeRefs(i);
                        refsHaveBeenWritten.put(i, true);
                    }
                    //write
                    i.writeTmpWays(line);
                }
            }
            // begin relation
            else if (line.contains("<relation ")) {
                target = "relations";
                skipRelation = false;
                relationLine = line;
                firstRef = 0;
                previousRef = 0;
                previousTn = 0;
                tiles.clear();
                members.clear();
                refsHaveBeenWritten.clear();
            }
            // member
            else if (line.contains("<member ")) {
                if (!skipRelation) {
                    attr = parseAttr(line);
                    ref = Long.valueOf(attr.get("ref"));

                    if (attr.get("type").equals("relation")) {
                        skipRelation = true;
                    } else if (attr.get("type").equals("way")) {
                        ArrayList<Long> nodes = wayNodes.get(ref);
                        if (nodes != null) {
                            for (Long node : nodes) {
                                //get tile
                                tn = n2tn.getTn(node);
                                if (tn > 0) {
                                    t = tilesMap.get(tn);
                                    if (t != null) {
                                        tiles.add(t);
                                    }

                                    //init
                                    if (!members.containsKey(t)) {
                                        ArrayList<RelationMember> mm = new ArrayList<RelationMember>();
                                        mm.add(new RelationMember("way", ref, attr.getOrDefault("role", "")));
                                        members.put(t, mm);
                                    }
                                }
                            }
                        }
                    } else if (attr.get("type").equals("node")) {
                        //get tile
                        tn = n2tn.getTn(ref);
                        if (tn > 0) {
                            t = tilesMap.get(tn);
                            if (t != null) {
                                tiles.add(t);
                            }

                            //init
                            if (!members.containsKey(t)) {
                                members.put(t, new ArrayList<RelationMember>());
                            }

                            members.get(t).add(new RelationMember("node", ref, attr.getOrDefault("role", "")));
                        }
                    }
                }
            }
            // end relation
            else if (line.contains("</relation")) {
                if (!skipRelation) {
                    //write all lines if not yet done (in each tile)
                    for (Tile i : tiles) {
                        if (!refsHaveBeenWritten.containsKey(i) || !refsHaveBeenWritten.get(i)) {
                            //write <way>, <nd>s
                            i.writeTmpRelations(relationLine);
                            writeMembers(i);
                            refsHaveBeenWritten.put(i, true);
                        }
                        //write
                        i.writeTmpRelations(line);
                    }
                }
            }
            //end
            else if (line.contains("</osm")) {
                break;
            }
            //tags
            else {
                if (target.equals("nodes")) {
                    if (slim) {
                        t.writeTmpNodes(line);
                    }
                    else {
                        storeNode(nodeId, t, line);
                    }
                } else if (target.equals("ways")) {
                    //write all lines if not yet done (in each tile)
                    for (Tile i : tiles) {
                        if (!refsHaveBeenWritten.containsKey(i) || !refsHaveBeenWritten.get(i)) {
                            //Closed ways: If the last nd equals the first nd, append to all segments
                            //the first (local) nd.
                            if (ref == firstRef && ref != refs.get(i).get(refs.get(i).size() - 1)) {
                                refs.get(i).add(refs.get(i).get(0));
                                refTn.get(i).add(refTn.get(i).get(0));
                            }
                            //debug
                            if (debug) {
                                System.out.println("closed way, write first nd again in tile "+tn);
                            }

                            //write <way>, <nd>s
                            i.writeTmpWays(wayLine);
                            writeRefs(i);
                            refsHaveBeenWritten.put(i, true);
                        }
                        //write
                        i.writeTmpWays(line);
                    }
                } else if (target.equals("relations")) {
                    if (!skipRelation) {
                        //write all lines if not yet done (in each tile)
                        for (Tile i : tiles) {
                            if (!refsHaveBeenWritten.containsKey(i) || !refsHaveBeenWritten.get(i)) {
                                //write <way>, <nd>s
                                i.writeTmpRelations(relationLine);
                                writeMembers(i);
                                refsHaveBeenWritten.put(i, true);
                            }
                            //write
                            i.writeTmpRelations(line);
                        }
                    }
                }
            }
            
            // performance status
            if (lineCount % lineThreshold == 0) {
                timeRunning = new Date().getTime() - startTime;
                startTime = new Date().getTime();

                System.out.println("read " + lineCount + " lines (" + lineThreshold + " in " + timeRunning + " ms)");
            }
        }
        
        System.out.println("writing nodes ...");
        
        for (Tile i : tilesMap.values()) {
            //close termsmp. writers
            i.nodesWriter.close();
            i.nodesExtraWriter.close();
            i.waysWriter.close();
            
            //create writer for output file
            i.tileWriter = new FileWriter(i.tileFn);
            i.writeOpening();
            
            //write nodes
            if (slim) {
                i.writeNodesFromTmp();
            }
            else {
                for (String s : i.nodesMap.values()) {
                    i.writeLine(s);
                }
            }
        }
        
        System.out.println("writing ways ...");
        
        for (Tile i : tilesMap.values()) {
            //write ways
            i.writeWaysFromTmp();
        }

        System.out.println("writing relations ...");
        
        for (Tile i : tilesMap.values()) {
            //write ways
            i.writeRelationsFromTmp();
        }
        
        System.out.println("closing ...");
        
        for (Tile i : tilesMap.values()) {
            i.writeClosingTags();
            i.removeTmpFiles();
        }
    }
    
    /**
     * storeNode: write node data to TreeMap 
     * @param ref
     * @param tn
     * @param s
     */
    void storeNode(long ref, Tile tile, String s) {
        if (tile.nodesMap.containsKey(ref)) {
            tile.nodesMap.put(ref, tile.nodesMap.get(ref) + "\n" + s);
        }
        else tile.nodesMap.put(ref, s);
    }

    /**
     * storeRemoteNode: write a single node entry from tile to remote's TreeMap
     * @param ref
     * @param tile
     * @param remote
     */
    void storeRemoteNode(long ref, Tile tile, Tile remote) {
        String s = tile.nodesMap.get(ref);

        if (remote.nodesMap.containsKey(ref)) {
            remote.nodesMap.put(ref, remote.nodesMap.get(ref) + "\n" + s);
        }
        else remote.nodesMap.put(ref, s);
    }
    
    /**
     * writeRefs: write <nd .../> lines to file
     * @param tile
     */
    void writeRefs(Tile tile) {
        //fix problem with first/last node of closed way being the last remote node
        if (ref == firstRef && ref != refs.get(tile).get(0) && ref == lastRemoteNodeAdded.get(tile)) {
            refs.get(tile).add(0, ref);
            refTn.get(tile).add(0, refTn.get(tile).get(refTn.get(tile).size() - 1));
        }
        for (int i = 0; i < refs.get(tile).size(); i++) {
            if (refTn.get(tile).size() > i && refTn.get(tile).get(i) != 0) {
                tile.writeTmpWays("     <nd ref=\"" + refs.get(tile).get(i) + "\" tn=\"" + refTn.get(tile).get(i) + "\"/>");
            }
            else {
                tile.writeTmpWays("     <nd ref=\"" + refs.get(tile).get(i) + "\"/>");
            }
        }
    }

    /**
     * writeRefs: write <member .../> lines to file
     * @param tile
     */
    void writeMembers(Tile tile) {
        ArrayList<RelationMember> obj = members.get(tile);
        if (obj != null) {
            for (int i = 0; i < obj.size(); i++) {
                tile.writeTmpRelations("     " + obj.get(i).toString());
            }
        }
    }
    
    /**
     * parseAttr: parse XML attributes from a line
     * @param line
     * @return key/value HashMap
     */
    public static HashMap<String, String> parseAttr(String line) {
        HashMap<String, String> result = new HashMap<String, String>();
        String[] attributes = line.trim().replaceAll("/>$", "").replaceAll(">$", "").split(" +");
        
        for (String s : attributes) {
            if (s.contains("=")) {
                String[] keyValue = s.replace("\"", "").split("=");
                if (keyValue.length > 1)
                    result.put(keyValue[0], keyValue[1]);
            }
        }
        return result;
    }
}
