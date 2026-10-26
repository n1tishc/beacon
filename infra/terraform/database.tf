# One free-tier Postgres for the three services; each owns its own schema, as locally.

resource "random_password" "database" {
  length  = 32
  special = false # it goes into a JDBC env var and an .env file; length gives the strength
}

resource "aws_security_group" "database" {
  name        = "beacon-database"
  description = "Postgres, reachable from the Beacon instance only"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "database_from_app" {
  security_group_id            = aws_security_group.database.id
  description                  = "Postgres, from the Beacon instance"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.app.id
}

resource "aws_db_subnet_group" "main" {
  name       = "beacon"
  subnet_ids = aws_subnet.database[*].id
}

resource "aws_db_instance" "main" {
  identifier     = "beacon"
  engine         = "postgres"
  engine_version = "17"
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  storage_type      = "gp2"
  storage_encrypted = true

  db_name  = "beacon"
  username = "beacon"
  password = random_password.database.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.database.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period    = 1
  copy_tags_to_snapshot      = true
  auto_minor_version_upgrade = true
  apply_immediately          = true

  # A demo environment that scripts/aws-destroy.sh must be able to remove completely.
  # In production: deletion_protection = true and a final snapshot.
  deletion_protection = false
  skip_final_snapshot = true
}
