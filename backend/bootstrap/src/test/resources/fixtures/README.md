`apache-poi-simple.doc` is the real binary Word 97–2003 test document `simple.doc` from Apache POI's `test-data/document` directory:

https://raw.githubusercontent.com/apache/poi/trunk/test-data/document/simple.doc

It is retained only as a parser regression fixture. The first eight bytes are the OLE Compound Document signature (`D0 CF 11 E0 A1 B1 1A E1`), distinguishing it from an RTF file renamed with a `.doc` extension.
