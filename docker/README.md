# Simulator Docker

This docker image can be used 

## Setup



## Execution:

```bash
simulator_image=$(docker build -t simulator:latest --rm -q .)

docker run \
      --network=mynet \
      --privileged \
      --volume $(pwd)/lvi:/home/lvi \
      --publish 127.0.0.1::161 \
      --detach \
      ${simulator_image} \
      bash /home/lvi/run.sh
```
