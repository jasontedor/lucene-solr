package org.apache.lucene.index;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.EOFException;
import java.io.IOException;
import java.util.Collections;
import java.util.Random;

import org.apache.lucene.analysis.MockAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.store.BaseDirectoryWrapper;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.MockDirectoryWrapper;
import org.apache.lucene.util.LineFileDocs;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.TestUtil;

/**
 * Test that the same file name, but from a different index, is detected as foreign.
 */
public class TestSwappedIndexFiles extends LuceneTestCase {
  public void test() throws Exception {
    Directory dir1 = newDirectory();
    Directory dir2 = newDirectory();

    if (dir1 instanceof MockDirectoryWrapper) {
      // otherwise we can have unref'd files left in the index that won't be visited when opening a reader and lead to scary looking false failures:
      ((MockDirectoryWrapper) dir1).setEnableVirusScanner(false);
    }
    if (dir2 instanceof MockDirectoryWrapper) {
      // otherwise we can have unref'd files left in the index that won't be visited when opening a reader and lead to scary looking false failures:
      ((MockDirectoryWrapper) dir2).setEnableVirusScanner(false);
    }

    // Disable CFS 80% of the time so we can truncate individual files, but the other 20% of the time we test truncation of .cfs/.cfe too:
    boolean useCFS = random().nextInt(5) == 1;

    // Use LineFileDocs so we (hopefully) get most Lucene features
    // tested, e.g. IntPoint was recently added to it:
    LineFileDocs docs = new LineFileDocs(random());
    Document doc = docs.nextDoc();
    long seed = random().nextLong();

    indexOneDoc(seed, dir1, doc, useCFS);
    indexOneDoc(seed, dir2, doc, useCFS);

    swapFiles(dir1, dir2);
    dir1.close();
    dir2.close();
  }

  private void indexOneDoc(long seed, Directory dir, Document doc, boolean useCFS) throws IOException {
    Random random = new Random(seed);
    IndexWriterConfig conf = newIndexWriterConfig(new MockAnalyzer(random));
    conf.setCodec(TestUtil.getDefaultCodec());

    if (useCFS == false) {
      conf.setUseCompoundFile(false);
      conf.getMergePolicy().setNoCFSRatio(0.0);
    } else {
      conf.setUseCompoundFile(true);
      conf.getMergePolicy().setNoCFSRatio(1.0);
    }

    RandomIndexWriter w = new RandomIndexWriter(random, dir, conf);
    w.addDocument(doc);
    w.close();
  }
  
  private void swapFiles(Directory dir1, Directory dir2) throws IOException {
    for(String name : dir1.listAll()) {
      if (name.equals(IndexWriter.WRITE_LOCK_NAME)) {
        continue;
      }
      swapOneFile(dir1, dir2, name);
    }
  }
  
  private void swapOneFile(Directory dir1, Directory dir2, String victim) throws IOException {
    try (BaseDirectoryWrapper dirCopy = newDirectory()) {
      dirCopy.setCheckIndexOnClose(false);

      // Copy all files from dir1 to dirCopy, except victim which we copy from dir2:
      for(String name : dir1.listAll()) {
        if (name.equals(victim) == false) {
          dirCopy.copyFrom(dir1, name, name, IOContext.DEFAULT);
        } else {
          dirCopy.copyFrom(dir2, name, name, IOContext.DEFAULT);
        }
        dirCopy.sync(Collections.singleton(name));
      }

      try {
        // NOTE: we .close so that if the test fails (truncation not detected) we don't also get all these confusing errors about open files:
        DirectoryReader.open(dirCopy).close();
        fail("wrong file " + victim + " not detected");
      } catch (CorruptIndexException | EOFException | IndexFormatTooOldException e) {
        // expected
      }

      // CheckIndex should also fail:
      try {
        TestUtil.checkIndex(dirCopy, true, true);
        fail("wrong file " + victim + " not detected");
      } catch (CorruptIndexException | EOFException | IndexFormatTooOldException e) {
        // expected
      }
    }
  }
}
