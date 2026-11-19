-- Instances that start at the same time run this file one after the other: CREATE ... IF NOT EXISTS is not safe
-- when two transactions create the same table at once. The lock is released when the transaction ends. The
-- number only has to be the same for every instance.
SELECT pg_advisory_xact_lock(7308327816128787041);

-- One row is one leaf value of one version of one document, in a namespace. The array positions of the value are
-- taken out of the path: $.tags[1] is path $.tags[] and indices {1}.
CREATE TABLE IF NOT EXISTS fragment
(
  namespace varchar COLLATE "POSIX"     NOT NULL,
  entity    varchar COLLATE "POSIX"     NOT NULL,
  id        varchar COLLATE "POSIX"     NOT NULL,
  version   timestamp(3) with time zone NOT NULL,
  path      varchar COLLATE "POSIX"     NOT NULL,
  indices   integer[]                   NOT NULL,
  type      smallint                    NOT NULL,
  value     bytea NULL,
  PRIMARY KEY (namespace, entity, id, version, path, indices)
);

-- Finds the documents that hold a value at a path. On a hash of the value, because an index row has a size limit
-- that a long value exceeds. The hash comes first, so that only a search for a value can use this index: with the
-- namespace and the entity first, PostgreSQL may pick it in place of the primary key for a read or a delete of
-- one document while the table has no statistics yet, and then walks every row of the entity.
CREATE INDEX IF NOT EXISTS fragment_value_idx ON fragment (sha256(value), namespace, entity, path);
