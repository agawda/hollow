/*
 *
 *  Copyright 2016 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.hollow.jsonadapter.chunker;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Executor;

public class JsonArrayChunker {
    
    private static final int DEFAULT_SEGMENT_LENGTH = 262144;
    private static final int SEGMENT_QUEUE_SIZE = 32;

    private final Reader reader;
    private final Queue<JsonArrayChunkerInputSegment> bufferSegments;
    private final Executor executor;
    private final int segmentLength;
    
    private JsonArrayChunkerInputSegment currentSegment;
    private long currentSegmentStartOffset;
    
    private boolean eofReached;
    
    public JsonArrayChunker(Reader reader, Executor executor) {
        this(reader, executor, DEFAULT_SEGMENT_LENGTH);
    }
    
    public JsonArrayChunker(Reader reader, Executor executor, int segmentLength) {
        this.reader = reader;
        this.bufferSegments = new LinkedList<JsonArrayChunkerInputSegment>();
        this.executor = executor;
        this.segmentLength = segmentLength;
    }

    
    public void initialize() throws IOException, InterruptedException {
        while(!eofReached && bufferSegments.size() < SEGMENT_QUEUE_SIZE) {
            fillOneSegment();
        }
        
        nextSegment();
    }
    
    @SuppressWarnings("resource")
    public Reader nextChunk() throws InterruptedException, IOException {
        while(!currentSegment.nextSpecialCharacter()) {
            if(!nextSegment())
                return null;
        }
        
        if(currentSegment.specialCharacter() != '{')
            throw new IllegalStateException("Bad json");
        
        int nestedObjectCount = 1;
        JsonArrayChunkReader chunkReader = new JsonArrayChunkReader(currentSegment, currentSegment.specialCharacterIteratorPosition());
        
        boolean insideQuotes = false;
        long lastEscapeCharacterLocation = Long.MIN_VALUE;

        while(nestedObjectCount > 0) {
            while(!currentSegment.nextSpecialCharacter()) {
                if(!nextSegment())
                    throw new IllegalStateException("Bad json");
                chunkReader.addSegment(currentSegment);
            }
            
            switch(currentSegment.specialCharacter()) {
            case '{':
                if(!insideQuotes)
                    nestedObjectCount++;
                break;
            case '}':
                if(!insideQuotes)
                    nestedObjectCount--;
                break;
            case '\"':
                long currentLocation = currentSegmentStartOffset + currentSegment.specialCharacterIteratorPosition();
                if(lastEscapeCharacterLocation != (currentLocation - 1)) {
                    insideQuotes = !insideQuotes;
                }
                break;
            case '\\':
                currentLocation = currentSegmentStartOffset + currentSegment.specialCharacterIteratorPosition();
                if(lastEscapeCharacterLocation != (currentLocation - 1))
                    lastEscapeCharacterLocation = currentLocation;
                break;
            }
        }
        
        chunkReader.setEndOffset(currentSegment.specialCharacterIteratorPosition() + 1);
        
        return chunkReader;
    }
    
    private boolean nextSegment() throws InterruptedException, IOException {
        if(bufferSegments.size() == 0)
            return false;

        if(!eofReached)
            fillOneSegment();

        currentSegmentStartOffset += segmentLength;
        currentSegment = bufferSegments.remove();
        currentSegment.waitForDefinedOffsets();
        return true;
    }
    
    private void fillOneSegment() throws IOException {
        final JsonArrayChunkerInputSegment seg = new JsonArrayChunkerInputSegment(segmentLength);
        eofReached = seg.fill(reader);
        bufferSegments.add(seg);
        
        executor.execute(new Runnable() {
            public void run() {
                seg.findSpecialCharacterOffsets();
            }
        });
    }
    
}
