#!/usr/bin/env bash
set -euo pipefail

echo "[1/4] build backend"
mvn clean package -DskipTests

echo "[2/4] train model"
python scripts/ml/train_model.py

echo "[3/4] build frontend"
cd frontend
npm install
npm run build
cd ..

echo "[4/4] start app"
java -jar target/award-log-1.0-SNAPSHOT.jar
