#!/bin/bash
# TODO: remove jep
set -e

echo "#1 start example for MNIST"
#timer
start=$(date "+%s")
if [ -f tmp/data/MNIST ]; then
  echo "MNIST already exists"
else
  wget -nv $FTP_URI/analytics-zoo-data/mnist/train-labels-idx1-ubyte.gz -P tmp/data/MNIST/raw
  wget -nv $FTP_URI/analytics-zoo-data/mnist/train-images-idx3-ubyte.gz -P tmp/data/MNIST/raw
  wget -nv $FTP_URI/analytics-zoo-data/mnist/t10k-labels-idx1-ubyte.gz -P tmp/data/MNIST/raw
  wget -nv $FTP_URI/analytics-zoo-data/mnist/t10k-images-idx3-ubyte.gz -P tmp/data/MNIST/raw
fi

python ${BIGDL_ROOT}/python/orca/example/torchmodel/train/mnist/main.py --dir tmp/data --epochs 1

now=$(date "+%s")
time1=$((now - start))

echo "#2 start example for orca Cifar10"
#timer
start=$(date "+%s")
if [ -d ${BIGDL_ROOT}/python/orca/example/learn/pytorch/cifar10/data ]; then
  echo "Cifar10 already exists"
else
  wget -nv $FTP_URI/analytics-zoo-data/cifar10.zip -P ${BIGDL_ROOT}/python/orca/example/learn/pytorch/cifar10
  unzip ${BIGDL_ROOT}/python/orca/example/learn/pytorch/cifar10/cifar10.zip
fi

python ${BIGDL_ROOT}/python/orca/example/learn/pytorch/cifar10/cifar10.py --batch_size 256 --epochs 1 --backend bigdl

now=$(date "+%s")
time2=$((now - start))

echo "#3 start example for orca Fashion-MNIST"
#timer
start=$(date "+%s")
if [ -d ${BIGDL_ROOT}/python/orca/example/learn/pytorch/fashion_mnist/data ]
then
    echo "fashion-mnist dataset already exists"
else
    wget -nv $FTP_URI/analytics-zoo-data/data/fashion-mnist.zip -P ${BIGDL_ROOT}/python/orca/example/learn/pytorch/fashion_mnist/
    unzip ${BIGDL_ROOT}/python/orca/example/learn/pytorch/fashion_mnist/fashion-mnist.zip
fi

sed "s/epochs=5/epochs=1/g;s/batch_size=4/batch_size=256/g" \
    ${BIGDL_ROOT}/python/orca/example/learn/pytorch/fashion_mnist/fashion_mnist.py \
    > ${BIGDL_ROOT}/python/orca/example/learn/pytorch/fashion_mnist/fashion_mnist_tmp.py

python ${BIGDL_ROOT}/python/orca/example/learn/pytorch/fashion_mnist/fashion_mnist_tmp.py --backend bigdl

now=$(date "+%s")
time3=$((now - start))

echo "#4 start example for orca Super Resolution"
#timer
start=$(date "+%s")
if [ ! -f BSDS300-images.tgz ]; then
  wget -nv $FTP_URI/analytics-zoo-data/BSDS300-images.tgz
fi
if [ ! -d dataset/BSDS300/images ]; then
  mkdir dataset
  tar -xzf BSDS300-images.tgz -C dataset
fi

python ${BIGDL_ROOT}/python/orca/example/learn/pytorch/super_resolution/super_resolution.py --epochs 1 --backend bigdl

now=$(date "+%s")
time4=$((now - start))

echo "#5 start test for orca bigdl resnet-finetune"
#timer
start=$(date "+%s")
#prepare dataset
wget $FTP_URI/analytics-zoo-data/data/cats_and_dogs_filtered.zip -P tmp/data
unzip -q tmp/data/cats_and_dogs_filtered.zip -d tmp/data
mkdir tmp/data/cats_and_dogs_filtered/samples
cp tmp/data/cats_and_dogs_filtered/train/cats/cat.7* tmp/data/cats_and_dogs_filtered/samples
cp tmp/data/cats_and_dogs_filtered/train/dogs/dog.7* tmp/data/cats_and_dogs_filtered/samples
#prepare model
if [ -d ${HOME}/.cache/torch/hub/checkpoints/resnet18-5c106cde.pth ]; then
  echo "resnet model found."
else
  if [ ! -d ${HOME}/.cache/torch/hub/checkpoints ]; then
    mkdir ${HOME}/.cache/torch/hub/checkpoints
  fi
  wget $FTP_URI/analytics-zoo-models/pytorch/resnet18-5c106cde.pth -P ${HOME}/.cache/torch/hub/checkpoints
fi
#run the example
python ${BIGDL_ROOT}/python/orca/example/torchmodel/train/resnet_finetune/resnet_finetune.py tmp/data/cats_and_dogs_filtered/samples
exit_status=$?
if [ $exit_status -ne 0 ]; then
  clear_up
  echo "orca bigdl resnet-finetune"
  exit $exit_status
fi
now=$(date "+%s")
time5=$((now - start))

echo "#6 start example for orca brainMRI"
if [ -f ${BIGDL_ROOT}/python/orca/example/learn/pytorch/brainMRI/kaggle_3m ]
then
    echo "kaggle_3m already exists"
else
    wget -nv $FTP_URI/analytics-zoo-data/kaggle_3m.zip -P ${BIGDL_ROOT}/python/orca/example/learn/pytorch/brainMRI
    unzip ${BIGDL_ROOT}/python/orca/example/learn/pytorch/brainMRI/kaggle_3m.zip
fi

start=$(date "+%s")
export PYTHONPATH=${BIGDL_ROOT}/python/orca/example/learn/pytorch/brainMRI:$PYTHONPATH
python ${BIGDL_ROOT}/python/orca/example/learn/pytorch/brainMRI/brainMRI.py --backend bigdl --epochs 1
now=$(date "+%s")
time6=$((now-start))

echo "#1 MNIST example time used:$time1 seconds"
echo "#2 orca Cifar10 example time used:$time2 seconds"
echo "#3 orca Fashion-MNIST example time used:$time3 seconds"
echo "#4 orca Super Resolution example time used:$time4 seconds"
echo "#5 torchmodel resnet-finetune time used:$time5 seconds"
echo "#6 orca brainMRI example time used:$time6 seconds"
