from pathlib import Path
p=Path('app/src/test/kotlin/dev/tqmane/befuck/ui/PreviewTransportTest.kt');s=p.read_text().replace('assertEquals(300000, source.size)','assertEquals(300000L, source.getSize())').replace(' { source.size }',' { source.getSize() }');p.write_text(s)
