import torch

class ExposureComputer:
    def __init__(self):
        pass

    def log_transform(self, x):
        return torch.log(x + 1.0)

    def compute_exposure(self, x, sensitivities):
        """
        x: tensor [batch, features] or [features]
        s: tensor [features]
        """
        x = self.log_transform(x)
        return torch.sum(x * sensitivities, dim=-1)
