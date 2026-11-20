-- Run once by the PostgreSQL image, when its data directory is empty. The image makes the user lds and the
-- database lds; these are the databases of the saga log and the transaction log.
CREATE DATABASE sagalog;
CREATE DATABASE txlog;
